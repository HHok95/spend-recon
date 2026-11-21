package com.hhok.spendrecon

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
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
class DecisionApiTest : PostgresIntegrationTest() {
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
    fun `two parallel approves at the same version produce one 200 and one 409`() {
        val fixture = suggested()
        val before = conflicts()
        val body = """{"version":0,"actor":"sam"}"""
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results =
                listOf(uniqueKey(), uniqueKey())
                    .map { key -> pool.submit<HttpResult> { post("/matches/${fixture.matchId}/approve", key, body) } }
                    .map { it.get(30, TimeUnit.SECONDS) }
            assertThat(results.map { it.status }).containsExactlyInAnyOrder(200, 409)
            val conflict = results.first { it.status == 409 }
            val problem = json.readTree(conflict.body)
            assertThat(problem["status"].asInt()).isEqualTo(409)
            assertThat(problem["title"].asText()).isEqualTo("Conflict")
            assertThat(problem["detail"].asText()).contains("stale")
            assertThat(conflict.header("Content-Type")).contains("application/problem+json")
            assertThat(json.readTree(results.first { it.status == 200 }.body)["status"].asText()).isEqualTo("APPROVED")
        } finally {
            pool.shutdownNow()
        }
        assertThat(conflicts() - before).isEqualTo(1.0)
        assertThat(eventTypes(fixture.matchId)).containsExactly("SUGGESTED", "APPROVED")
        assertThat(outboxCount(fixture.matchId)).isEqualTo(1)
        assertThat(matchVersion(fixture.matchId)).isEqualTo(1)
        assertThat(transactionStatus(fixture.transactionId)).isEqualTo("APPROVED")
    }

    @Test
    fun `approve writes one audit row and one outbox row and replays`() {
        val fixture = suggested()
        val body = """{"version":0,"actor":"sam","reason":"confirmed"}"""
        val key = uniqueKey()
        val created = post("/matches/${fixture.matchId}/approve", key, body)
        assertThat(created.status).isEqualTo(200)
        val approved = json.readTree(created.body)
        assertThat(approved["status"].asText()).isEqualTo("APPROVED")
        assertThat(approved["version"].asLong()).isEqualTo(1)
        assertThat(approved["matchId"].asText()).isEqualTo(fixture.matchId)
        assertThat(eventTypes(fixture.matchId)).containsExactly("SUGGESTED", "APPROVED")
        assertThat(eventReasons(fixture.matchId)).containsExactly(null, "confirmed")
        assertThat(eventActors(fixture.matchId)).containsExactly("system:matching", "sam")
        val outbox = outboxRow(fixture.matchId)
        assertThat(outbox.eventType).isEqualTo("MatchConfirmed")
        assertThat(outbox.publishedAt).isNull()
        assertThat(outbox.payload).contains(fixture.matchId).contains("sam")
        assertThat(decidedBy(fixture.matchId)).isEqualTo("sam")

        val replay = post("/matches/${fixture.matchId}/approve", key, body)
        assertThat(replay.status).isEqualTo(200)
        assertThat(replay.body).isEqualTo(created.body)
        assertThat(eventTypes(fixture.matchId)).containsExactly("SUGGESTED", "APPROVED")
        assertThat(outboxCount(fixture.matchId)).isEqualTo(1)

        val clash = post("/matches/${fixture.matchId}/approve", key, """{"version":0,"actor":"sam","reason":"other"}""")
        assertThat(clash.status).isEqualTo(422)
        assertThat(eventTypes(fixture.matchId)).hasSize(2)
    }

    @Test
    fun `a matched score under the auto-approve threshold can be approved`() {
        val owner = uniqueOwner()
        createDocument(owner, merchant = "duane", amount = "20.00", tax = "2.00")
        val txn = createTransaction(owner, merchant = "dwayne", amount = "20.00", tax = "2.00")
        val reconciled = json.readTree(reconcile(txn).body)
        assertThat(reconciled["status"].asText()).isEqualTo("MATCHED")
        val matchId = reconciled["matchId"].asText()
        val approved = post("/matches/$matchId/approve", uniqueKey(), """{"version":0,"actor":"sam"}""")
        assertThat(approved.status).isEqualTo(200)
        assertThat(json.readTree(approved.body)["status"].asText()).isEqualTo("APPROVED")
        assertThat(eventTypes(matchId)).containsExactly("MATCHED", "APPROVED")
        assertThat(outboxCount(matchId)).isEqualTo(1)
    }

    @Test
    fun `an exception approve needs a reason`() {
        val owner = uniqueOwner()
        createDocument(owner, amount = "55.00", tax = "5.00")
        val txn = createTransaction(owner, amount = "50.00", tax = "5.00")
        val reconciled = json.readTree(reconcile(txn).body)
        assertThat(reconciled["status"].asText()).isEqualTo("EXCEPTION")
        val matchId = reconciled["matchId"].asText()
        val before = conflicts()

        val missing = post("/matches/$matchId/approve", uniqueKey(), """{"version":0,"actor":"sam"}""")
        assertThat(missing.status).isEqualTo(422)
        assertThat(json.readTree(missing.body)["detail"].asText()).contains("non-empty reason")
        val blank = post("/matches/$matchId/approve", uniqueKey(), """{"version":0,"actor":"sam","reason":"   "}""")
        assertThat(blank.status).isEqualTo(422)
        assertThat(conflicts()).isEqualTo(before)
        assertThat(matchVersion(matchId)).isZero()
        assertThat(eventTypes(matchId)).containsExactly("EXCEPTION")
        assertThat(outboxCount(matchId)).isZero()
        assertThat(transactionStatus(txn)).isEqualTo("EXCEPTION")

        val approved =
            post(
                "/matches/$matchId/approve",
                uniqueKey(),
                """{"version":0,"actor":"sam","reason":"override the amount"}""",
            )
        assertThat(approved.status).isEqualTo(200)
        assertThat(json.readTree(approved.body)["version"].asLong()).isEqualTo(1)
        assertThat(eventTypes(matchId)).containsExactly("EXCEPTION", "APPROVED")
        assertThat(eventReasons(matchId)).containsExactly(null, "override the amount")
        assertThat(outboxCount(matchId)).isEqualTo(1)
        assertThat(transactionStatus(txn)).isEqualTo("APPROVED")
        assertThat(conflicts()).isEqualTo(before)
    }

    @Test
    fun `reject frees the receipt and puts the charge back to unmatched`() {
        val fixture = suggested()
        val rejected =
            post(
                "/matches/${fixture.matchId}/reject",
                uniqueKey(),
                """{"version":0,"actor":"sam","reason":"not this receipt"}""",
            )
        assertThat(rejected.status).isEqualTo(200)
        val body = json.readTree(rejected.body)
        assertThat(body["status"].asText()).isEqualTo("REJECTED")
        assertThat(body["version"].asLong()).isEqualTo(1)
        assertThat(eventTypes(fixture.matchId)).containsExactly("SUGGESTED", "REJECTED")
        assertThat(eventReasons(fixture.matchId)).containsExactly(null, "not this receipt")
        assertThat(outboxCount(fixture.matchId)).isZero()
        assertThat(transactionStatus(fixture.transactionId)).isEqualTo("UNMATCHED")
        assertThat(matchStatus(fixture.matchId)).isEqualTo("REJECTED")

        val second = createTransaction(fixture.owner, merchant = "Cafe", amount = "8.00", tax = null)
        val taken = json.readTree(reconcile(second).body)
        assertThat(taken["status"].asText()).isEqualTo("SUGGESTED")
        assertThat(matchDocument(taken["matchId"].asText())).isEqualTo(fixture.receiptId)
        assertThat(matchDocument(fixture.matchId)).isEqualTo(fixture.receiptId)
        assertThat(matchStatus(fixture.matchId)).isEqualTo("REJECTED")
    }

    @Test
    fun `reject then reconcile reuses the match row`() {
        val fixture = suggested()
        val rejected =
            post(
                "/matches/${fixture.matchId}/reject",
                uniqueKey(),
                """{"version":0,"actor":"sam","reason":"not this receipt"}""",
            )
        assertThat(rejected.status).isEqualTo(200)

        val again = reconcile(fixture.transactionId)
        assertThat(again.status).isEqualTo(200)
        val body = json.readTree(again.body)
        assertThat(body["matchId"].asText()).isEqualTo(fixture.matchId)
        assertThat(body["status"].asText()).isEqualTo("SUGGESTED")
        assertThat(body["score"].decimalValue()).isEqualByComparingTo("0.700")
        assertThat(matchCount(fixture.transactionId)).isEqualTo(1)
        assertThat(matchVersion(fixture.matchId)).isEqualTo(2)
        assertThat(matchDocument(fixture.matchId)).isEqualTo(fixture.receiptId)
        assertThat(matchStatus(fixture.matchId)).isEqualTo("SUGGESTED")
        assertThat(decidedBy(fixture.matchId)).isNull()
        assertThat(transactionStatus(fixture.transactionId)).isEqualTo("SUGGESTED")
        assertThat(outboxCount(fixture.matchId)).isZero()
        assertThat(eventTypes(fixture.matchId)).containsExactly("SUGGESTED", "REJECTED", "SUGGESTED")
        assertThat(eventActors(fixture.matchId)).containsExactly("system:matching", "sam", "system:matching")

        val history = json.readTree(get("/matches/${fixture.matchId}/history").body)
        assertThat(history).hasSize(3)
        assertThat(history[1]["eventType"].asText()).isEqualTo("REJECTED")
        assertThat(history[2]["eventType"].asText()).isEqualTo("SUGGESTED")
        assertThat(history[2]["before"]["status"].asText()).isEqualTo("REJECTED")
        assertThat(history[2]["before"]["documentId"].asText()).isEqualTo(fixture.receiptId)
        assertThat(history[2]["after"]["status"].asText()).isEqualTo("SUGGESTED")
        assertThat(history[2]["after"]["documentId"].asText()).isEqualTo(fixture.receiptId)
    }

    @Test
    fun `two concurrent re-reconciles of a rejected charge leave one row`() {
        val fixture = suggested()
        val rejected =
            post(
                "/matches/${fixture.matchId}/reject",
                uniqueKey(),
                """{"version":0,"actor":"sam","reason":"try again"}""",
            )
        assertThat(rejected.status).isEqualTo(200)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results =
                listOf(uniqueKey(), uniqueKey())
                    .map { key ->
                        pool.submit<HttpResult> {
                            post("/reconcile/${fixture.transactionId}", key, "{}")
                        }
                    }.map { it.get(30, TimeUnit.SECONDS) }
            assertThat(results.map { it.status }).containsExactlyInAnyOrder(200, 409)
            val winner = json.readTree(results.first { it.status == 200 }.body)
            assertThat(winner["matchId"].asText()).isEqualTo(fixture.matchId)
            assertThat(winner["status"].asText()).isEqualTo("SUGGESTED")
            val conflict = results.first { it.status == 409 }
            assertThat(conflict.body).contains("already has a match")
            assertThat(conflict.header("Content-Type")).contains("application/problem+json")
            assertThat(matchCount(fixture.transactionId)).isEqualTo(1)
            assertThat(eventTypes(fixture.matchId)).containsExactly("SUGGESTED", "REJECTED", "SUGGESTED")
            assertThat(matchVersion(fixture.matchId)).isEqualTo(2)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `a stale version conflicts and a terminal match does not`() {
        val fixture = suggested()
        val before = conflicts()
        val stale = post("/matches/${fixture.matchId}/approve", uniqueKey(), """{"version":4,"actor":"sam"}""")
        assertThat(stale.status).isEqualTo(409)
        assertThat(json.readTree(stale.body)["detail"].asText()).contains("stale")
        assertThat(conflicts() - before).isEqualTo(1.0)
        assertThat(matchVersion(fixture.matchId)).isZero()
        assertThat(eventTypes(fixture.matchId)).containsExactly("SUGGESTED")

        val approved = post("/matches/${fixture.matchId}/approve", uniqueKey(), """{"version":0,"actor":"sam"}""")
        assertThat(approved.status).isEqualTo(200)
        val conflictsAfterApprove = conflicts()
        val again = post("/matches/${fixture.matchId}/approve", uniqueKey(), """{"version":1,"actor":"sam"}""")
        assertThat(again.status).isEqualTo(409)
        assertThat(json.readTree(again.body)["detail"].asText()).contains("cannot be approved")
        val rejected =
            post(
                "/matches/${fixture.matchId}/reject",
                uniqueKey(),
                """{"version":1,"actor":"sam","reason":"too late"}""",
            )
        assertThat(rejected.status).isEqualTo(409)
        assertThat(json.readTree(rejected.body)["detail"].asText()).contains("cannot be rejected")
        assertThat(conflicts()).isEqualTo(conflictsAfterApprove)
        assertThat(matchVersion(fixture.matchId)).isEqualTo(1)
        assertThat(eventTypes(fixture.matchId)).containsExactly("SUGGESTED", "APPROVED")
    }

    @Test
    fun `missing credentials and a missing reject reason are client errors`() {
        val fixture = suggested()
        val open = post("/matches/${fixture.matchId}/approve", uniqueKey(), """{"version":0,"actor":"sam"}""", apiKey = null)
        assertThat(open.status).isEqualTo(401)
        val noKey = post("/matches/${fixture.matchId}/approve", null, """{"version":0,"actor":"sam"}""")
        assertThat(noKey.status).isEqualTo(400)
        val noReason = post("/matches/${fixture.matchId}/reject", uniqueKey(), """{"version":0,"actor":"sam"}""")
        assertThat(noReason.status).isEqualTo(400)
        assertThat(matchStatus(fixture.matchId)).isEqualTo("SUGGESTED")
        assertThat(eventTypes(fixture.matchId)).containsExactly("SUGGESTED")
    }

    private fun suggested(): Fixture {
        val owner = uniqueOwner()
        val receipt = createDocument(owner, merchant = "Cafe", amount = "8.00", tax = null)
        val txn = createTransaction(owner, merchant = "Cafe", amount = "8.00", tax = null)
        val response = reconcile(txn)
        assertThat(response.status).isEqualTo(200)
        val body = json.readTree(response.body)
        assertThat(body["status"].asText()).isEqualTo("SUGGESTED")
        assertThat(body["score"].decimalValue()).isEqualByComparingTo("0.700")
        return Fixture(owner, txn, receipt, body["matchId"].asText())
    }

    private fun createTransaction(
        owner: String,
        merchant: String = "Cafe",
        amount: String,
        tax: String?,
    ): String {
        val taxJson = if (tax == null) "" else ""","tax":"$tax""""
        val body =
            """{"cardOwnerId":"$owner","merchant":"$merchant","amount":"$amount","currency":"AUD","txnDate":"2026-11-21"$taxJson}"""
        val response = post("/transactions", uniqueKey(), body)
        assertThat(response.status).isEqualTo(201)
        return json.readTree(response.body)["id"].asText()
    }

    private fun createDocument(
        owner: String,
        merchant: String = "Cafe",
        amount: String,
        tax: String?,
    ): String {
        val taxJson = if (tax == null) "" else ""","tax":"$tax""""
        val body =
            """{"kind":"RECEIPT","cardOwnerId":"$owner","merchant":"$merchant","amount":"$amount","currency":"AUD","docDate":"2026-11-21"$taxJson}"""
        val response = post("/documents", uniqueKey(), body)
        assertThat(response.status).isEqualTo(201)
        return json.readTree(response.body)["id"].asText()
    }

    private fun reconcile(txnId: String): HttpResult = post("/reconcile/$txnId", uniqueKey(), "{}")

    private fun get(path: String): HttpResult {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:$port$path"))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build()
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        return HttpResult(response.statusCode(), response.body(), response.headers())
    }

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
        val response = HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString())
        return HttpResult(response.statusCode(), response.body(), response.headers())
    }

    private fun eventTypes(matchId: String): List<String> = column(matchId, "event_type")

    private fun eventActors(matchId: String): List<String> = column(matchId, "actor")

    private fun eventReasons(matchId: String): List<String?> =
        jdbc.query(
            "SELECT reason FROM reconciliation_events WHERE match_id = ? ORDER BY id",
            { rs, _ -> rs.getString("reason") },
            UUID.fromString(matchId),
        )

    private fun column(
        matchId: String,
        name: String,
    ): List<String> =
        jdbc.query(
            "SELECT $name FROM reconciliation_events WHERE match_id = ? ORDER BY id",
            { rs, _ -> rs.getString(name) },
            UUID.fromString(matchId),
        )

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

    private fun matchCount(transactionId: String): Long =
        jdbc.queryForObject(
            "SELECT count(*) FROM matches WHERE transaction_id = ?",
            Long::class.java,
            UUID.fromString(transactionId),
        )!!

    private fun matchVersion(matchId: String): Long =
        jdbc.queryForObject(
            "SELECT version FROM matches WHERE id = ?",
            Long::class.java,
            UUID.fromString(matchId),
        )!!

    private fun matchStatus(matchId: String): String =
        jdbc.queryForObject(
            "SELECT status FROM matches WHERE id = ?",
            String::class.java,
            UUID.fromString(matchId),
        )!!

    private fun matchDocument(matchId: String): String? =
        jdbc.queryForObject(
            "SELECT document_id::text FROM matches WHERE id = ?",
            String::class.java,
            UUID.fromString(matchId),
        )

    private fun decidedBy(matchId: String): String? =
        jdbc.queryForObject(
            "SELECT decided_by FROM matches WHERE id = ?",
            String::class.java,
            UUID.fromString(matchId),
        )

    private fun transactionStatus(id: String): String =
        jdbc.queryForObject(
            "SELECT status FROM card_transactions WHERE id = ?",
            String::class.java,
            UUID.fromString(id),
        )!!

    private fun conflicts(): Double = meterRegistry.find("optimistic_lock_conflicts").counter()?.count() ?: 0.0

    private fun uniqueOwner(): String = "owner-${UUID.randomUUID()}"

    private fun uniqueKey(): String = UUID.randomUUID().toString()

    private data class Fixture(
        val owner: String,
        val transactionId: String,
        val receiptId: String,
        val matchId: String,
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
        fun header(name: String): String? = headers.firstValue(name).orElse(null)
    }
}
