package com.hhok.spendrecon

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.hhok.spendrecon.application.ACTOR_AUTO_APPROVE
import com.hhok.spendrecon.application.ACTOR_MATCHING
import com.hhok.spendrecon.application.IdempotencyEndpoints
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
class DuplicateReceiptApiTest : PostgresIntegrationTest() {
    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var properties: ReconProperties

    @Autowired
    private lateinit var meterRegistry: MeterRegistry

    @org.springframework.boot.test.web.server.LocalServerPort
    private var port: Int = 0

    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    private val json = ObjectMapper()

    @Test
    fun `the same receipt number and merchant is a 409 including case and whitespace`() {
        val owner = uniqueOwner()
        val key = uniqueKey()
        val created =
            post(
                "/documents",
                key,
                documentBody(owner = owner, merchant = "Cafe Nero", number = "INV-100"),
            )
        assertThat(created.status).isEqualTo(201)
        val createdId = idOf(created.body)

        val variant =
            post(
                "/documents",
                uniqueKey(),
                documentBody(owner = uniqueOwner(), merchant = "  cafe   nero ", number = " inv-100 "),
            )
        assertThat(variant.status).isEqualTo(409)
        assertThat(variant.header("Content-Type")).contains("application/problem+json")
        val problem = json.readTree(variant.body)
        assertThat(problem["status"].asInt()).isEqualTo(409)
        assertThat(problem["type"].asText()).isEqualTo("urn:spend-recon:duplicate-document")
        assertThat(problem["title"].asText()).isEqualTo("Duplicate document")
        assertThat(problem["detail"].asText()).contains("receipt or invoice number")
        assertThat(variant.body).isNotEqualTo(created.body)
        assertThat(countDocuments(owner)).isEqualTo(1)
        assertThat(storedNumber(createdId)).isEqualTo("INV-100")

        val otherNumber =
            post(
                "/documents",
                uniqueKey(),
                documentBody(owner = owner, merchant = "Cafe Nero", number = "INV-101"),
            )
        assertThat(otherNumber.status).isEqualTo(201)

        val otherMerchant =
            post(
                "/documents",
                uniqueKey(),
                documentBody(owner = owner, merchant = "Other Shop", number = "INV-100"),
            )
        assertThat(otherMerchant.status).isEqualTo(201)

        val duplicateKey = uniqueKey()
        val duplicateBody = documentBody(owner = uniqueOwner(), merchant = "Cafe Nero", number = "INV-100")
        val duplicate = post("/documents", duplicateKey, duplicateBody)
        assertThat(duplicate.status).isEqualTo(409)
        assertThat(countKeys(duplicateKey, IdempotencyEndpoints.DOCUMENTS)).isZero()
        val duplicateAgain = post("/documents", duplicateKey, duplicateBody)
        assertThat(duplicateAgain.status).isEqualTo(409)
        val reusedKey =
            post(
                "/documents",
                duplicateKey,
                documentBody(owner = uniqueOwner(), merchant = "Fresh Merchant", number = "INV-100"),
            )
        assertThat(reusedKey.status).isEqualTo(201)

        val replay = post("/documents", key, documentBody(owner = owner, merchant = "Cafe Nero", number = "INV-100"))
        assertThat(replay.status).isEqualTo(201)
        assertThat(replay.body).isEqualTo(created.body)
        assertThat(countNumber(merchantNorm = "cafe nero", numberNorm = "inv-100")).isEqualTo(1)
    }

    @Test
    fun `a near match with a different or missing receipt number is not auto approved`() {
        val differentNumbers = uniqueOwner()
        val numberedMerchant = uniqueMerchant()
        val first = createDocument(differentNumbers, merchant = numberedMerchant, number = "R-1")
        val second = createDocument(differentNumbers, merchant = numberedMerchant, number = "R-2")
        assertPossibleDuplicate(differentNumbers, numberedMerchant, first, second)

        val missingNumbers = uniqueOwner()
        val bareMerchant = uniqueMerchant()
        val bareFirst = createDocument(missingNumbers, merchant = bareMerchant, number = null)
        val bareSecond = createDocument(missingNumbers, merchant = bareMerchant, number = null)
        assertPossibleDuplicate(missingNumbers, bareMerchant, bareFirst, bareSecond)
    }

    @Test
    fun `a receipt already held still makes the same-day copy an exception`() {
        val owner = uniqueOwner()
        val merchant = uniqueMerchant()
        val first = createDocument(owner, merchant = merchant, number = "R-1")
        val firstCharge = createTransaction(owner, merchant = merchant)
        val approved = reconcile(firstCharge)
        val approvedBody = json.readTree(approved.body)
        assertThat(approvedBody["status"].asText()).isEqualTo("APPROVED")

        val second = createDocument(owner, merchant = merchant, number = "R-2")
        val secondCharge = createTransaction(owner, merchant = merchant)
        val beforeApprovals = counter("recon_auto_approvals")
        val flagged = reconcile(secondCharge)
        assertThat(flagged.status).isEqualTo(200)
        val body = json.readTree(flagged.body)
        assertThat(body["status"].asText()).isEqualTo("EXCEPTION")
        assertThat(body["rulesFired"].map { it.asText() }).contains("R7_DUPLICATE")
        val match = matchOf(body["matchId"].asText())
        assertThat(match["status"].asText()).isEqualTo("EXCEPTION")
        assertThat(match["exceptionType"].asText()).isEqualTo("POSSIBLE_DUPLICATE")
        assertThat(match["documentId"].asText()).isEqualTo(second)
        assertThat(match["decidedBy"].isNull).isTrue()
        assertThat(match["score"].isNull).isFalse()
        assertThat(counter("recon_auto_approvals")).isEqualTo(beforeApprovals)
        assertThat(outboxCount(body["matchId"].asText())).isZero()
        val firstMatch = matchOf(approvedBody["matchId"].asText())
        assertThat(firstMatch["status"].asText()).isEqualTo("APPROVED")
        assertThat(firstMatch["documentId"].asText()).isEqualTo(first)
    }

    @Test
    fun `a repeat purchase on a different date is approved`() {
        val owner = uniqueOwner()
        val merchant = uniqueMerchant()
        val earlyReceipt = createDocument(owner, merchant = merchant, date = "2026-11-01", number = "R-1")
        val lateReceipt = createDocument(owner, merchant = merchant, date = "2026-11-21", number = "R-2")
        val earlyCharge = createTransaction(owner, merchant = merchant, date = "2026-11-01")
        val lateCharge = createTransaction(owner, merchant = merchant, date = "2026-11-21")

        val early = reconcile(earlyCharge)
        val late = reconcile(lateCharge)
        assertApproved(early, earlyReceipt)
        assertApproved(late, lateReceipt)
    }

    @Test
    fun `a trailing tab or a leading nbsp is the same number and spaces are no number`() {
        val owner = uniqueOwner()
        val merchant = uniqueMerchant()
        val created = post("/documents", uniqueKey(), documentBody(owner = owner, merchant = merchant, number = "INV-100"))
        assertThat(created.status).isEqualTo(201)
        val createdId = idOf(created.body)
        assertThat(storedNumber(createdId)).isEqualTo("INV-100")
        assertThat(storedNumberNorm(createdId)).isEqualTo("inv-100")

        val tab = post("/documents", uniqueKey(), documentBody(owner = owner, merchant = merchant, number = "INV-100\t"))
        assertThat(tab.status).isEqualTo(409)
        assertThat(json.readTree(tab.body)["type"].asText()).isEqualTo("urn:spend-recon:duplicate-document")

        val nbsp = post("/documents", uniqueKey(), documentBody(owner = owner, merchant = merchant, number = "\u00A0INV-100"))
        assertThat(nbsp.status).isEqualTo(409)
        assertThat(json.readTree(nbsp.body)["title"].asText()).isEqualTo("Duplicate document")

        val blank = post("/documents", uniqueKey(), documentBody(owner = owner, merchant = merchant, number = "   "))
        assertThat(blank.status).isEqualTo(201)
        val blankId = idOf(blank.body)
        assertThat(storedNumber(blankId)).isNull()
        assertThat(storedNumberNorm(blankId)).isNull()
        assertThat(countNumber(merchantNorm = merchant.lowercase(), numberNorm = "inv-100")).isEqualTo(1)
    }

    @Test
    fun `replaying an idempotency key still returns the stored response`() {
        val owner = uniqueOwner()
        val transactionKey = uniqueKey()
        val transactionBody = transactionBody(owner)
        val transaction = post("/transactions", transactionKey, transactionBody)
        assertThat(transaction.status).isEqualTo(201)
        val transactionReplay = post("/transactions", transactionKey, transactionBody)
        assertThat(transactionReplay.status).isEqualTo(201)
        assertThat(transactionReplay.body).isEqualTo(transaction.body)
        assertThat(countTransactions(owner)).isEqualTo(1)

        val transactionMismatch = post("/transactions", transactionKey, transactionBody(owner, amount = "9.00"))
        assertThat(transactionMismatch.status).isEqualTo(422)
        assertThat(countTransactions(owner)).isEqualTo(1)

        val documentKey = uniqueKey()
        val merchant = uniqueMerchant()
        val documentBody = documentBody(owner = owner, merchant = merchant, number = "KEEP-1")
        val document = post("/documents", documentKey, documentBody)
        assertThat(document.status).isEqualTo(201)
        val documentReplay = post("/documents", documentKey, documentBody)
        assertThat(documentReplay.status).isEqualTo(201)
        assertThat(documentReplay.body).isEqualTo(document.body)
        assertThat(countDocuments(owner)).isEqualTo(1)

        val documentMismatch = post("/documents", documentKey, documentBody(owner = owner, merchant = merchant, number = "KEEP-2"))
        assertThat(documentMismatch.status).isEqualTo(422)
        assertThat(countDocuments(owner)).isEqualTo(1)
    }

    private fun assertPossibleDuplicate(
        owner: String,
        merchant: String,
        firstDocument: String,
        secondDocument: String,
    ) {
        val charge = createTransaction(owner, merchant = merchant)
        val beforeApprovals = counter("recon_auto_approvals")
        val beforeExceptions = tagged("recon_exceptions", "type", "POSSIBLE_DUPLICATE")
        val reconciled = reconcile(charge)
        assertThat(reconciled.status).isEqualTo(200)
        val body = json.readTree(reconciled.body)
        assertThat(body["status"].asText()).isEqualTo("EXCEPTION")
        assertThat(body["score"].isNull).isFalse()
        assertThat(body["rulesFired"].map { it.asText() }).contains("R7_DUPLICATE")
        val matchId = body["matchId"].asText()
        val match = matchOf(matchId)
        assertThat(match["status"].asText()).isEqualTo("EXCEPTION")
        assertThat(match["exceptionType"].asText()).isEqualTo("POSSIBLE_DUPLICATE")
        assertThat(match["documentId"].asText()).isIn(firstDocument, secondDocument)
        assertThat(match["decidedBy"].isNull).isTrue()
        assertThat(counter("recon_auto_approvals")).isEqualTo(beforeApprovals)
        assertThat(tagged("recon_exceptions", "type", "POSSIBLE_DUPLICATE")).isEqualTo(beforeExceptions + 1.0)
        assertThat(outboxCount(matchId)).isZero()
        val history = json.readTree(get("/matches/$matchId/history").body)
        assertThat(history).hasSize(1)
        assertThat(history[0]["eventType"].asText()).isEqualTo("EXCEPTION")
        assertThat(history[0]["actor"].asText()).isEqualTo(ACTOR_MATCHING)
        assertThat(history[0]["after"]["exceptionType"].asText()).isEqualTo("POSSIBLE_DUPLICATE")
        assertThat(history[0]["after"]["rulesFired"].map { it.asText() }).contains("R7_DUPLICATE")
    }

    private fun assertApproved(
        response: HttpResult,
        documentId: String,
    ) {
        assertThat(response.status).isEqualTo(200)
        val body = json.readTree(response.body)
        assertThat(body["status"].asText()).isEqualTo("APPROVED")
        assertThat(body["rulesFired"].map { it.asText() }).doesNotContain("R7_DUPLICATE")
        val matchId = body["matchId"].asText()
        val match = matchOf(matchId)
        assertThat(match["documentId"].asText()).isEqualTo(documentId)
        assertThat(match["exceptionType"].isNull).isTrue()
        assertThat(match["decidedBy"].asText()).isEqualTo(ACTOR_AUTO_APPROVE)
        assertThat(outboxCount(matchId)).isEqualTo(1)
        val history = json.readTree(get("/matches/$matchId/history").body)
        assertThat(history).hasSize(2)
        assertThat(history[1]["eventType"].asText()).isEqualTo("APPROVED")
        assertThat(history[1]["actor"].asText()).isEqualTo(ACTOR_AUTO_APPROVE)
    }

    private fun createDocument(
        owner: String,
        merchant: String,
        date: String = "2026-11-21",
        number: String? = null,
    ): String {
        val response =
            post(
                "/documents",
                uniqueKey(),
                documentBody(owner = owner, merchant = merchant, date = date, number = number),
            )
        assertThat(response.status).isEqualTo(201)
        return idOf(response.body)
    }

    private fun createTransaction(
        owner: String,
        merchant: String,
        date: String = "2026-11-21",
    ): String {
        val response =
            post(
                "/transactions",
                uniqueKey(),
                transactionBody(owner = owner, merchant = merchant, date = date),
            )
        assertThat(response.status).isEqualTo(201)
        return idOf(response.body)
    }

    private fun reconcile(txnId: String): HttpResult = post("/reconcile/$txnId", uniqueKey(), "{}")

    private fun matchOf(matchId: String): JsonNode {
        val response = get("/matches/$matchId")
        assertThat(response.status).isEqualTo(200)
        return json.readTree(response.body)
    }

    private fun post(
        path: String,
        idempotencyKey: String,
        body: String,
    ): HttpResult {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:$port$path"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("X-API-Key", properties.apiKey)
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        return send(request)
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
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        return HttpResult(response.statusCode(), response.body(), response.headers())
    }

    private fun idOf(body: String): String = json.readTree(body)["id"].asText()

    private fun documentBody(
        owner: String,
        merchant: String = "Cafe",
        amount: String = "12.34",
        date: String = "2026-11-21",
        number: String? = null,
    ): String {
        val numberJson = if (number == null) "" else ""","documentNumber":${json.writeValueAsString(number)}"""
        return """{"kind":"RECEIPT","cardOwnerId":"$owner","merchant":"$merchant","amount":"$amount","currency":"AUD","docDate":"$date","tax":"1.10"$numberJson}"""
    }

    private fun transactionBody(
        owner: String,
        merchant: String = "Cafe",
        amount: String = "12.34",
        date: String = "2026-11-21",
    ): String =
        """{"cardOwnerId":"$owner","merchant":"$merchant","amount":"$amount","currency":"AUD","txnDate":"$date","tax":"1.10"}"""

    private fun countDocuments(owner: String): Long =
        jdbc.queryForObject(
            "SELECT count(*) FROM documents WHERE card_owner_id = ?",
            Long::class.java,
            owner,
        )!!

    private fun countTransactions(owner: String): Long =
        jdbc.queryForObject(
            "SELECT count(*) FROM card_transactions WHERE card_owner_id = ?",
            Long::class.java,
            owner,
        )!!

    private fun countKeys(
        key: String,
        endpoint: String,
    ): Long =
        jdbc.queryForObject(
            "SELECT count(*) FROM idempotency_keys WHERE key = ? AND endpoint = ?",
            Long::class.java,
            key,
            endpoint,
        )!!

    private fun countNumber(
        merchantNorm: String,
        numberNorm: String,
    ): Long =
        jdbc.queryForObject(
            """
            SELECT count(*)
            FROM documents
            WHERE merchant_norm = ? AND lower(btrim(document_number)) = ?
            """.trimIndent(),
            Long::class.java,
            merchantNorm,
            numberNorm,
        )!!

    private fun storedNumber(id: String): String? = storedColumn(id, "document_number")

    private fun storedNumberNorm(id: String): String? = storedColumn(id, "document_number_norm")

    private fun storedColumn(
        id: String,
        column: String,
    ): String? =
        jdbc.query(
            "SELECT $column FROM documents WHERE id = ?",
            { rs, _ -> rs.getString(column) },
            UUID.fromString(id),
        ).single()

    private fun outboxCount(matchId: String): Long =
        jdbc.queryForObject(
            "SELECT count(*) FROM outbox WHERE aggregate_id = ?",
            Long::class.java,
            UUID.fromString(matchId),
        )!!

    private fun counter(name: String): Double = meterRegistry.find(name).counter()?.count() ?: 0.0

    private fun tagged(
        name: String,
        tag: String,
        value: String,
    ): Double = meterRegistry.find(name).tag(tag, value).counter()?.count() ?: 0.0

    private fun uniqueOwner(): String = "owner-${UUID.randomUUID()}"

    private fun uniqueMerchant(): String = "merchant-${UUID.randomUUID()}"

    private fun uniqueKey(): String = UUID.randomUUID().toString()

    private class HttpResult(
        val status: Int,
        val body: String,
        private val headers: java.net.http.HttpHeaders,
    ) {
        fun header(name: String): String? = headers.firstValue(name).orElse(null)
    }
}
