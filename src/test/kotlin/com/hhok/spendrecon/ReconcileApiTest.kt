package com.hhok.spendrecon

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.hhok.spendrecon.application.ACTOR_AUTO_APPROVE
import com.hhok.spendrecon.application.ACTOR_MATCHING
import com.hhok.spendrecon.domain.RuleSet
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
class ReconcileApiTest : PostgresIntegrationTest() {
    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var properties: ReconProperties

    @Autowired
    private lateinit var meterRegistry: MeterRegistry

    @org.springframework.boot.test.web.server.LocalServerPort
    private var port: Int = 0

    private val json = ObjectMapper()

    @Test
    fun `zero candidates are an exception with a null score and no auto approval`() {
        val owner = uniqueOwner()
        val txn = createTransaction(owner, amount = "50.00", tax = "5.00")
        val beforeApprovals = counter("recon_auto_approvals")
        val reconciled = reconcile(txn)
        assertThat(reconciled.status).isEqualTo(200)
        val body = json.readTree(reconciled.body)
        assertThat(body["status"].asText()).isEqualTo("EXCEPTION")
        assertThat(body["score"].isNull).isTrue()
        assertThat(reconciled.body).contains("\"score\": null")

        val matchId = body["matchId"].asText()
        val stored = matchRow(matchId)
        assertThat(stored.documentId).isNull()
        assertThat(stored.score).isNull()
        assertThat(stored.exceptionType).isEqualTo("NO_CANDIDATE")
        assertThat(stored.decidedBy).isNull()
        assertThat(stored.status).isEqualTo("EXCEPTION")
        assertThat(eventActors(matchId)).containsExactly(ACTOR_MATCHING)
        assertThat(outboxCount(matchId)).isZero()
        assertThat(counter("recon_auto_approvals")).isEqualTo(beforeApprovals)
        assertThat(transactionStatus(txn)).isEqualTo("EXCEPTION")

        val history = json.readTree(get("/matches/$matchId/history").body)
        assertThat(history).hasSize(1)
        assertThat(history[0]["after"]["score"].isNull).isTrue()
        assertThat(history[0]["after"]["exceptionType"].asText()).isEqualTo("NO_CANDIDATE")
        assertThat(history[0]["after"]["ruleSetVersion"].asText()).isEqualTo(RuleSet.VERSION)
    }

    @Test
    fun `an amount mismatch holds the receipt and the next charge does not take it`() {
        val owner = uniqueOwner()
        val receipt = createDocument(owner, amount = "55.00", tax = "5.00")
        val chargeA = createTransaction(owner, amount = "50.00", tax = "5.00")
        val beforeApprovals = counter("recon_auto_approvals")
        val first = reconcile(chargeA)
        assertThat(first.status).isEqualTo(200)
        val firstBody = json.readTree(first.body)
        assertThat(firstBody["status"].asText()).isEqualTo("EXCEPTION")
        val matchA = firstBody["matchId"].asText()
        val stored = matchRow(matchA)
        assertThat(stored.documentId).isEqualTo(receipt)
        assertThat(stored.exceptionType).isEqualTo("AMOUNT_MISMATCH")
        assertThat(stored.score).isEqualByComparingTo("1.000")
        assertThat(stored.decidedBy).isNull()
        assertThat(eventActors(matchA)).containsExactly(ACTOR_MATCHING)
        assertThat(counter("recon_auto_approvals")).isEqualTo(beforeApprovals)

        val chargeB = createTransaction(owner, amount = "55.00", tax = "5.00")
        val second = reconcile(chargeB)
        assertThat(second.status).isEqualTo(200)
        val secondBody = json.readTree(second.body)
        assertThat(secondBody["status"].asText()).isEqualTo("EXCEPTION")
        assertThat(secondBody["score"].isNull).isTrue()
        val storedB = matchRow(secondBody["matchId"].asText())
        assertThat(storedB.documentId).isNull()
        assertThat(storedB.exceptionType).isEqualTo("NO_CANDIDATE")
        assertThat(matchRow(matchA).documentId).isEqualTo(receipt)
    }

    @Test
    fun `two parallel reconciles compete for one receipt without a server error`() {
        val owner = uniqueOwner()
        val receipt = createDocument(owner, amount = "40.00", tax = "4.00")
        val left = createTransaction(owner, amount = "40.00", tax = "4.00")
        val right = createTransaction(owner, amount = "40.00", tax = "4.00")
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results =
                listOf(left, right)
                    .map { txn -> pool.submit<HttpResult> { reconcile(txn) } }
                    .map { it.get(30, TimeUnit.SECONDS) }
            assertThat(results.map { it.status }).containsOnly(200)
            val bodies = results.map { json.readTree(it.body) }
            val holders = bodies.map { matchRow(it["matchId"].asText()).documentId }
            assertThat(holders.filterNotNull()).containsExactly(receipt)
            assertThat(holders.count { it == null }).isEqualTo(1)
            val loser = bodies.first { matchRow(it["matchId"].asText()).documentId == null }
            assertThat(loser["status"].asText()).isEqualTo("EXCEPTION")
            assertThat(loser["score"].isNull).isTrue()
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `a matched score at or above the default threshold is approved and audited`() {
        assertThat(properties.autoApprove.threshold).isEqualByComparingTo("0.95")
        val owner = uniqueOwner()
        val receipt = createDocument(owner, merchant = "Cafe Nero", amount = "12.34", tax = "1.10")
        val txn = createTransaction(owner, merchant = "Cafe Nero", amount = "12.34", tax = "1.10")
        val beforeMatches = tagged("recon_matches", "result", "MATCHED")
        val beforeApprovals = counter("recon_auto_approvals")
        val created = reconcile(txn)
        assertThat(created.status).isEqualTo(200)
        val body = json.readTree(created.body)
        assertThat(body["status"].asText()).isEqualTo("APPROVED")
        assertThat(body["score"].decimalValue()).isEqualByComparingTo("1.000")
        assertThat(body["rulesFired"].map { it.asText() }).contains(
            "R1_CURRENCY",
            "R2_OWNER",
            "R3_AMOUNT",
            "R4_DATE",
            "R5_MERCHANT",
            "R6_TAX",
        )
        val matchId = body["matchId"].asText()
        val detail = json.readTree(get("/matches/$matchId").body)
        assertThat(detail["version"].asLong()).isEqualTo(0)
        assertThat(detail["documentId"].asText()).isEqualTo(receipt)
        assertThat(detail["decidedBy"].asText()).isEqualTo(ACTOR_AUTO_APPROVE)
        assertThat(detail["status"].asText()).isEqualTo("APPROVED")
        assertThat(transactionStatus(txn)).isEqualTo("APPROVED")
        assertThat(eventActors(matchId)).containsExactly(ACTOR_MATCHING, ACTOR_AUTO_APPROVE)

        val history = json.readTree(get("/matches/$matchId/history").body)
        assertThat(history).hasSize(2)
        val approved = history[1]
        assertThat(approved["eventType"].asText()).isEqualTo("APPROVED")
        assertThat(approved["actor"].asText()).isEqualTo(ACTOR_AUTO_APPROVE)
        assertThat(approved["after"]["score"].decimalValue()).isEqualByComparingTo("1.000")
        assertThat(approved["after"]["threshold"].decimalValue()).isEqualByComparingTo("0.95")
        assertThat(approved["after"]["ruleSetVersion"].asText()).isEqualTo(RuleSet.VERSION)
        assertThat(approved["after"]["rulesFired"].map { it.asText() }).contains("R5_MERCHANT", "R6_TAX")
        assertThat(approved["rulesFired"].map { it.asText() }).contains("R1_CURRENCY")

        val outbox = outboxRow(matchId)
        assertThat(outbox.eventType).isEqualTo("MatchConfirmed")
        assertThat(outbox.publishedAt).isNull()
        assertThat(outbox.payload).contains(matchId)
        assertThat(tagged("recon_matches", "result", "MATCHED") - beforeMatches).isEqualTo(1.0)
        assertThat(counter("recon_auto_approvals") - beforeApprovals).isEqualTo(1.0)

        val replay = reconcile(txn, key = headerKey(created), body = "{}")
        assertThat(replay.status).isEqualTo(200)
        assertThat(replay.body).isEqualTo(created.body)
        assertThat(eventCount(matchId)).isEqualTo(2)

        val clash = reconcile(txn, key = uniqueKey(), body = "{}")
        assertThat(clash.status).isEqualTo(409)
        assertThat(clash.body).contains("already has a match")
        assertThat(clash.header("Retry-After")).isNull()
    }

    @Test
    fun `a matched score just under the threshold stays matched`() {
        val owner = uniqueOwner()
        createDocument(owner, merchant = "duane", amount = "20.00", tax = "2.00")
        val txn = createTransaction(owner, merchant = "dwayne", amount = "20.00", tax = "2.00")
        val created = reconcile(txn)
        val body = json.readTree(created.body)
        assertThat(created.status).isEqualTo(200)
        val score = body["score"].decimalValue()
        assertThat(score).isGreaterThanOrEqualTo(BigDecimal("0.850"))
        assertThat(score).isLessThan(properties.autoApprove.threshold)
        assertThat(body["status"].asText()).isEqualTo("MATCHED")
        val matchId = body["matchId"].asText()
        assertThat(matchRow(matchId).decidedBy).isNull()
        assertThat(eventActors(matchId)).containsExactly(ACTOR_MATCHING)
        assertThat(outboxCount(matchId)).isZero()
    }

    @Test
    fun `a suggested result is never auto approved`() {
        val owner = uniqueOwner()
        createDocument(owner, merchant = "Cafe", amount = "8.00", tax = null)
        val txn = createTransaction(owner, merchant = "Cafe", amount = "8.00", tax = null)
        val created = reconcile(txn)
        val body = json.readTree(created.body)
        assertThat(body["status"].asText()).isEqualTo("SUGGESTED")
        assertThat(body["score"].decimalValue()).isEqualByComparingTo("0.700")
        val matchId = body["matchId"].asText()
        assertThat(matchRow(matchId).decidedBy).isNull()
        assertThat(eventActors(matchId)).containsExactly(ACTOR_MATCHING)
        assertThat(outboxCount(matchId)).isZero()
    }

    @Test
    fun `writes require the api key and a missing transaction does not burn the key`() {
        val missingKey = reconcile(UUID.randomUUID().toString(), apiKey = null)
        assertThat(missingKey.status).isEqualTo(401)
        assertThat(missingKey.header("Content-Type")).contains("application/problem+json")

        val unknown = UUID.randomUUID().toString()
        val key = uniqueKey()
        val missing = reconcile(unknown, key = key)
        assertThat(missing.status).isEqualTo(404)
        assertThat(missing.body).contains("No transaction")
        assertThat(countKeys(key)).isZero()

        val open = get("/matches?status=APPROVED")
        assertThat(open.status).isEqualTo(200)
    }

    @Test
    fun `the same key replays and a different body is 422`() {
        val owner = uniqueOwner()
        createDocument(owner, amount = "3.00", tax = "0.30")
        val txn = createTransaction(owner, amount = "3.00", tax = "0.30")
        val key = uniqueKey()
        val created = reconcile(txn, key = key, body = "{}")
        assertThat(created.status).isEqualTo(200)
        val mismatch = reconcile(txn, key = key, body = """{"note":"other"}""")
        assertThat(mismatch.status).isEqualTo(422)
        assertThat(countMatches(txn)).isEqualTo(1)
    }

    @Test
    fun `list and history are readable without an api key`() {
        val owner = uniqueOwner()
        createDocument(owner, amount = "6.00", tax = "0.60")
        val txn = createTransaction(owner, amount = "6.00", tax = "0.60")
        val matchId = json.readTree(reconcile(txn).body)["matchId"].asText()
        val page = json.readTree(get("/matches?status=APPROVED&page=0&size=20").body)
        assertThat(page["content"].map { it["matchId"].asText() }).contains(matchId)
        assertThat(page["content"].first { it["matchId"].asText() == matchId }["version"].asLong()).isEqualTo(0)
        val filtered = json.readTree(get("/matches?status=EXCEPTION").body)
        assertThat(filtered["content"].map { it["matchId"].asText() }).doesNotContain(matchId)

        val scrape = get("/actuator/prometheus")
        assertThat(scrape.status).isEqualTo(200)
        assertThat(scrape.body).contains("recon_matches_total")
        assertThat(scrape.body).contains("recon_auto_approvals_total")
        assertThat(scrape.body).contains("recon_exceptions_total")
        assertThat(scrape.body).contains("recon_match_latency_seconds")
    }

    @Test
    fun `a tie is suggested and a bill is not a candidate`() {
        val owner = uniqueOwner()
        createDocument(owner, amount = "7.00", tax = "0.70", merchant = "Same")
        createDocument(owner, amount = "7.00", tax = "0.70", merchant = "Same")
        val txn = createTransaction(owner, amount = "7.00", tax = "0.70", merchant = "Same")
        val body = json.readTree(reconcile(txn).body)
        assertThat(body["status"].asText()).isEqualTo("SUGGESTED")
        val matchId = body["matchId"].asText()
        val history = json.readTree(get("/matches/$matchId/history").body)
        val tied = history[0]["after"]["tiedDocumentIds"].map { UUID.fromString(it.asText()) }
        assertThat(tied).hasSize(2)
        assertThat(UUID.fromString(matchRow(matchId).documentId)).isEqualTo(tied.min())
        assertThat(matchRow(matchId).decidedBy).isNull()

        val billOwner = uniqueOwner()
        createDocument(billOwner, amount = "9.00", tax = "0.90", kind = "BILL")
        val billTxn = createTransaction(billOwner, amount = "9.00", tax = "0.90")
        val billMatch = json.readTree(reconcile(billTxn).body)
        assertThat(billMatch["status"].asText()).isEqualTo("EXCEPTION")
        assertThat(billMatch["score"].isNull).isTrue()
    }

    private fun createTransaction(
        owner: String,
        merchant: String = "Cafe",
        amount: String,
        tax: String?,
    ): String {
        val body = transactionJson(owner, merchant, amount, tax)
        val response = post("/transactions", uniqueKey(), body)
        assertThat(response.status).isEqualTo(201)
        return json.readTree(response.body)["id"].asText()
    }

    private fun createDocument(
        owner: String,
        merchant: String = "Cafe",
        amount: String,
        tax: String?,
        kind: String = "RECEIPT",
    ): String {
        val response = post("/documents", uniqueKey(), documentJson(owner, merchant, amount, tax, kind))
        assertThat(response.status).isEqualTo(201)
        return json.readTree(response.body)["id"].asText()
    }

    private fun reconcile(
        txnId: String,
        key: String = uniqueKey(),
        body: String = "{}",
        apiKey: String? = properties.apiKey,
    ): HttpResult = post("/reconcile/$txnId", key, body, apiKey)

    private fun post(
        path: String,
        idempotencyKey: String?,
        body: String,
        apiKey: String? = properties.apiKey,
    ): HttpResult {
        val builder =
            HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:$port$path"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
        if (apiKey != null) {
            builder.header("X-API-Key", apiKey)
        }
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey)
        }
        val result = send(builder.build())
        result.sentKey = idempotencyKey
        return result
    }

    private fun get(path: String): HttpResult {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:$port$path"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build()
        return send(request)
    }

    private fun send(request: HttpRequest): HttpResult {
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        return HttpResult(response.statusCode(), response.body(), response.headers())
    }

    private fun matchRow(id: String): StoredMatch =
        jdbc.queryForObject(
            """
            SELECT document_id, status, score, exception_type, decided_by, version
            FROM matches
            WHERE id = ?
            """.trimIndent(),
            { rs, _ ->
                StoredMatch(
                    documentId = rs.getString("document_id"),
                    status = rs.getString("status"),
                    score = rs.getBigDecimal("score"),
                    exceptionType = rs.getString("exception_type"),
                    decidedBy = rs.getString("decided_by"),
                    version = rs.getLong("version"),
                )
            },
            UUID.fromString(id),
        )!!

    private fun eventActors(matchId: String): List<String> =
        jdbc.query(
            """
            SELECT actor
            FROM reconciliation_events
            WHERE match_id = ?
            ORDER BY id
            """.trimIndent(),
            { rs, _ -> rs.getString("actor") },
            UUID.fromString(matchId),
        )

    private fun eventCount(matchId: String): Long =
        jdbc.queryForObject(
            "SELECT count(*) FROM reconciliation_events WHERE match_id = ?",
            Long::class.java,
            UUID.fromString(matchId),
        )!!

    private fun outboxCount(matchId: String): Long =
        jdbc.queryForObject(
            "SELECT count(*) FROM outbox WHERE aggregate_id = ?",
            Long::class.java,
            UUID.fromString(matchId),
        )!!

    private fun outboxRow(matchId: String): StoredOutbox =
        jdbc.queryForObject(
            """
            SELECT event_type, payload::text AS payload, published_at
            FROM outbox
            WHERE aggregate_id = ?
            """.trimIndent(),
            { rs, _ ->
                StoredOutbox(
                    eventType = rs.getString("event_type"),
                    payload = rs.getString("payload"),
                    publishedAt = rs.getTimestamp("published_at"),
                )
            },
            UUID.fromString(matchId),
        )!!

    private fun transactionStatus(id: String): String =
        jdbc.queryForObject(
            "SELECT status FROM card_transactions WHERE id = ?",
            String::class.java,
            UUID.fromString(id),
        )!!

    private fun countKeys(key: String): Long =
        jdbc.queryForObject(
            "SELECT count(*) FROM idempotency_keys WHERE key = ? AND endpoint LIKE 'POST /reconcile/%'",
            Long::class.java,
            key,
        )!!

    private fun countMatches(txnId: String): Long =
        jdbc.queryForObject(
            "SELECT count(*) FROM matches WHERE transaction_id = ?",
            Long::class.java,
            UUID.fromString(txnId),
        )!!

    private fun counter(name: String): Double = meterRegistry.find(name).counter()?.count() ?: 0.0

    private fun tagged(
        name: String,
        tag: String,
        value: String,
    ): Double = meterRegistry.find(name).tag(tag, value).counter()?.count() ?: 0.0

    private fun headerKey(result: HttpResult): String = result.sentKey ?: error("request had no idempotency key")

    private fun transactionJson(
        owner: String,
        merchant: String,
        amount: String,
        tax: String?,
    ): String {
        val taxJson = if (tax == null) "" else ""","tax":"$tax""""
        return """{"cardOwnerId":"$owner","merchant":"$merchant","amount":"$amount","currency":"AUD","txnDate":"2026-11-21"$taxJson}"""
    }

    private fun documentJson(
        owner: String,
        merchant: String,
        amount: String,
        tax: String?,
        kind: String,
    ): String {
        val taxJson = if (tax == null) "" else ""","tax":"$tax""""
        return """{"kind":"$kind","cardOwnerId":"$owner","merchant":"$merchant","amount":"$amount","currency":"AUD","docDate":"2026-11-21"$taxJson}"""
    }

    private fun uniqueOwner(): String = "owner-${UUID.randomUUID()}"

    private fun uniqueKey(): String = UUID.randomUUID().toString()

    private data class StoredMatch(
        val documentId: String?,
        val status: String,
        val score: BigDecimal?,
        val exceptionType: String?,
        val decidedBy: String?,
        val version: Long,
    )

    private data class StoredOutbox(
        val eventType: String,
        val payload: String,
        val publishedAt: java.sql.Timestamp?,
    )

    private class HttpResult(
        val status: Int,
        val body: String,
        private val headers: java.net.http.HttpHeaders,
    ) {
        var sentKey: String? = null

        fun header(name: String): String? = headers.firstValue(name).orElse(null)
    }
}

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["recon.auto-approve.threshold=1.000"],
)
class AutoApproveAtThresholdTest : PostgresIntegrationTest() {
    @org.springframework.boot.test.web.server.LocalServerPort
    private var port: Int = 0

    private val json = ObjectMapper()

    @Test
    fun `a perfect score at a threshold of 1 is approved`() {
        val owner = "owner-${UUID.randomUUID()}"
        val txn = postCharge(owner)
        postReceipt(owner)
        val response = postReconcile(txn)
        assertThat(response.status).isEqualTo(200)
        val body = json.readTree(response.body)
        assertThat(body["status"].asText()).isEqualTo("APPROVED")
        assertThat(body["score"].decimalValue()).isEqualByComparingTo("1.000")
        val history = history(body["matchId"].asText())
        assertThat(history).hasSize(2)
        assertThat(history[1]["actor"].asText()).isEqualTo(ACTOR_AUTO_APPROVE)
        assertThat(history[1]["after"]["threshold"].decimalValue()).isEqualByComparingTo("1.000")
        assertThat(history[1]["after"]["ruleSetVersion"].asText()).isEqualTo(RuleSet.VERSION)
    }

    private fun postCharge(owner: String): String = createdId("/transactions", charge(owner))

    private fun postReceipt(owner: String) {
        createdId("/documents", receipt(owner))
    }

    private fun createdId(
        path: String,
        body: String,
    ): String {
        val response = send(path, body)
        assertThat(response.status).isEqualTo(201)
        return json.readTree(response.body)["id"].asText()
    }

    private fun postReconcile(txnId: String): HttpCall = send("/reconcile/$txnId", "{}")

    private fun history(matchId: String): JsonNode {
        val response = get("/matches/$matchId/history")
        assertThat(response.status).isEqualTo(200)
        return json.readTree(response.body)
    }

    private fun send(
        path: String,
        body: String,
    ): HttpCall {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:$port$path"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("X-API-Key", "local-dev-key")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        return HttpCall(response.statusCode(), response.body())
    }

    private fun get(path: String): HttpCall {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:$port$path"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build()
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        return HttpCall(response.statusCode(), response.body())
    }

    private fun charge(owner: String) =
        """{"cardOwnerId":"$owner","merchant":"Cafe","amount":"4.00","currency":"AUD","txnDate":"2026-11-21","tax":"0.40"}"""

    private fun receipt(owner: String) =
        """{"kind":"RECEIPT","cardOwnerId":"$owner","merchant":"Cafe","amount":"4.00","currency":"AUD","docDate":"2026-11-21","tax":"0.40"}"""

    private data class HttpCall(
        val status: Int,
        val body: String,
    )
}

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["recon.auto-approve.threshold=1.1"],
)
class AutoApproveDisabledTest : PostgresIntegrationTest() {
    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @org.springframework.boot.test.web.server.LocalServerPort
    private var port: Int = 0

    private val json = ObjectMapper()

    @Test
    fun `a threshold above 1 leaves a perfect match unapproved`() {
        val owner = "owner-${UUID.randomUUID()}"
        val txn = created("/transactions", charge(owner))
        created("/documents", receipt(owner))
        val response = post("/reconcile/$txn", "{}")
        assertThat(response.status).isEqualTo(200)
        val body = json.readTree(response.body)
        assertThat(body["status"].asText()).isEqualTo("MATCHED")
        assertThat(body["score"].decimalValue()).isEqualByComparingTo("1.000")
        val matchId = UUID.fromString(body["matchId"].asText())
        val actors =
            jdbc.query(
                "SELECT actor FROM reconciliation_events WHERE match_id = ? ORDER BY id",
                { rs, _ -> rs.getString(1) },
                matchId,
            )
        assertThat(actors).containsExactly(ACTOR_MATCHING)
        val outbox =
            jdbc.queryForObject(
                "SELECT count(*) FROM outbox WHERE aggregate_id = ?",
                Long::class.java,
                matchId,
            )
        assertThat(outbox).isZero()
        val approvals = get("/actuator/prometheus").body
        assertThat(approvals.lineSequence().none { it.startsWith("recon_auto_approvals_total ") && it.contains(" 1") }).isTrue()
    }

    private fun created(
        path: String,
        body: String,
    ): String {
        val response = post(path, body)
        assertThat(response.status).isEqualTo(201)
        return json.readTree(response.body)["id"].asText()
    }

    private fun post(
        path: String,
        body: String,
    ): HttpCall {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:$port$path"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("X-API-Key", "local-dev-key")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        return HttpCall(response.statusCode(), response.body())
    }

    private fun get(path: String): HttpCall {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:$port$path"))
                .GET()
                .build()
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        return HttpCall(response.statusCode(), response.body())
    }

    private fun charge(owner: String) =
        """{"cardOwnerId":"$owner","merchant":"Cafe","amount":"4.00","currency":"AUD","txnDate":"2026-11-21","tax":"0.40"}"""

    private fun receipt(owner: String) =
        """{"kind":"RECEIPT","cardOwnerId":"$owner","merchant":"Cafe","amount":"4.00","currency":"AUD","docDate":"2026-11-21","tax":"0.40"}"""

    private data class HttpCall(
        val status: Int,
        val body: String,
    )
}

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["recon.auto-approve.threshold=0.50"],
)
@AutoConfigureObservability
class SuggestedStaysUnapprovedTest : PostgresIntegrationTest() {
    @Autowired
    private lateinit var meterRegistry: MeterRegistry

    @org.springframework.boot.test.web.server.LocalServerPort
    private var port: Int = 0

    private val json = ObjectMapper()

    @Test
    fun `a suggested score above a low threshold is not approved`() {
        val owner = "owner-${UUID.randomUUID()}"
        val txn = created("/transactions", charge(owner))
        created("/documents", receipt(owner))
        val response = post("/reconcile/$txn", "{}")
        val body = json.readTree(response.body)
        assertThat(response.status).isEqualTo(200)
        assertThat(body["status"].asText()).isEqualTo("SUGGESTED")
        assertThat(body["score"].decimalValue()).isEqualByComparingTo("0.700")
        assertThat(meterRegistry.find("recon_auto_approvals").counter()?.count() ?: 0.0).isEqualTo(0.0)
    }

    private fun created(
        path: String,
        body: String,
    ): String {
        val response = post(path, body)
        assertThat(response.status).isEqualTo(201)
        return json.readTree(response.body)["id"].asText()
    }

    private fun post(
        path: String,
        body: String,
    ): HttpCall {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:$port$path"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("X-API-Key", "local-dev-key")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        return HttpCall(response.statusCode(), response.body())
    }

    private fun charge(owner: String) =
        """{"cardOwnerId":"$owner","merchant":"Cafe","amount":"4.00","currency":"AUD","txnDate":"2026-11-21"}"""

    private fun receipt(owner: String) =
        """{"kind":"RECEIPT","cardOwnerId":"$owner","merchant":"Cafe","amount":"4.00","currency":"AUD","docDate":"2026-11-21"}"""

    private data class HttpCall(
        val status: Int,
        val body: String,
    )
}
