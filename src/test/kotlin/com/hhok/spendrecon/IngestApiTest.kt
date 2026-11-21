package com.hhok.spendrecon

import com.fasterxml.jackson.databind.ObjectMapper
import com.hhok.spendrecon.application.IdempotencyEndpoints
import com.hhok.spendrecon.application.requestSha256
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
import java.nio.charset.StandardCharsets
import java.sql.DriverManager
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
class IngestApiTest : PostgresIntegrationTest() {
    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var properties: ReconProperties

    @Autowired
    private lateinit var meterRegistry: MeterRegistry

    @org.springframework.boot.test.web.server.LocalServerPort
    private var port: Int = 0

    private val http: HttpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    private val json = ObjectMapper()

    @Test
    fun `health stays open and writes require the api key`() {
        val health = get("/actuator/health")
        assertThat(health.status).isEqualTo(200)
        assertThat(health.body).contains("\"status\":\"UP\"")

        val owner = uniqueOwner()
        val body = transactionBody(owner = owner)
        val missing = post("/transactions", idempotencyKey = "missing-key", body = body, apiKey = null)
        assertThat(missing.status).isEqualTo(401)
        assertThat(missing.header("Content-Type")).contains("application/problem+json")
        assertThat(json.readTree(missing.body)["status"].asInt()).isEqualTo(401)

        val wrong = post("/transactions", "wrong-key", body, apiKey = "not-the-key")
        assertThat(wrong.status).isEqualTo(401)
        assertThat(countTransactions(owner)).isZero()
    }

    @Test
    fun `a transaction stores minor units and replays the same response`() {
        val owner = uniqueOwner()
        val body =
            transactionBody(
                owner = owner,
                merchant = "  Cafe   Nero ",
                amount = "12.34",
                currency = "aud",
                tax = "1.10",
            )
        val created = post("/transactions", uniqueKey(), body)
        assertThat(created.status).isEqualTo(201)
        val createdJson = json.readTree(created.body)
        assertThat(createdJson["status"].asText()).isEqualTo("UNMATCHED")
        val stored = storedTransaction(createdJson["id"].asText())
        assertThat(stored.minor).isEqualTo(1234)
        assertThat(stored.currency).isEqualTo("AUD")
        assertThat(stored.tax).isEqualTo(110)
        assertThat(stored.norm).isEqualTo("cafe nero")
        assertThat(stored.status).isEqualTo("UNMATCHED")

        val replay = post("/transactions", headerKey(created), body)
        assertThat(replay.status).isEqualTo(201)
        assertThat(replay.body).isEqualTo(created.body)
        assertThat(countTransactions(owner)).isEqualTo(1)
    }

    @Test
    fun `jpy and bhd use the currency scale`() {
        val jpyOwner = uniqueOwner()
        val jpy = post("/transactions", uniqueKey(), transactionBody(owner = jpyOwner, amount = "500", currency = "JPY"))
        assertThat(jpy.status).isEqualTo(201)
        assertThat(storedTransaction(idOf(jpy.body)).minor).isEqualTo(500)

        val rejected = post("/transactions", uniqueKey(), transactionBody(owner = uniqueOwner(), amount = "500.0", currency = "JPY"))
        assertThat(rejected.status).isEqualTo(400)
        assertThat(rejected.body).contains("decimal places")

        val bhdOwner = uniqueOwner()
        val bhd = post("/transactions", uniqueKey(), transactionBody(owner = bhdOwner, amount = "1.234", currency = "BHD"))
        assertThat(bhd.status).isEqualTo(201)
        assertThat(storedTransaction(idOf(bhd.body)).minor).isEqualTo(1234)
        assertThat(storedTransaction(idOf(bhd.body)).currency).isEqualTo("BHD")
    }

    @Test
    fun `a json number and an extra decimal place are rejected`() {
        val owner = uniqueOwner()
        val number =
            post(
                "/transactions",
                uniqueKey(),
                """{"cardOwnerId":"$owner","merchant":"Cafe","amount":12.34,"currency":"AUD","txnDate":"2026-11-21"}""",
            )
        assertThat(number.status).isEqualTo(400)
        assertThat(number.body).contains("string")
        assertThat(countTransactions(owner)).isZero()

        val extra = post("/transactions", uniqueKey(), transactionBody(owner = owner, amount = "12.345"))
        assertThat(extra.status).isEqualTo(400)
        assertThat(countTransactions(owner)).isZero()
    }

    @Test
    fun `the idempotency key is required and a different body is 422`() {
        val owner = uniqueOwner()
        val body = transactionBody(owner = owner, amount = "8.00")
        val missing = post("/transactions", idempotencyKey = null, body = body)
        assertThat(missing.status).isEqualTo(400)
        assertThat(missing.body).contains("Idempotency-Key")
        assertThat(countTransactions(owner)).isZero()

        val key = uniqueKey()
        val created = post("/transactions", key, body)
        assertThat(created.status).isEqualTo(201)
        val mismatch = post("/transactions", key, transactionBody(owner = owner, amount = "8.01"))
        assertThat(mismatch.status).isEqualTo(422)
        assertThat(json.readTree(mismatch.body)["status"].asInt()).isEqualTo(422)
        assertThat(countTransactions(owner)).isEqualTo(1)
    }

    @Test
    fun `documents are created and the same idempotency key is per endpoint`() {
        val owner = uniqueOwner()
        val key = uniqueKey()
        val transaction = post("/transactions", key, transactionBody(owner = owner, amount = "3.00"))
        assertThat(transaction.status).isEqualTo(201)

        val documentBody = documentBody(owner = owner, amount = "3.00", kind = "RECEIPT")
        val document = post("/documents", key, documentBody)
        assertThat(document.status).isEqualTo(201)
        assertThat(json.readTree(document.body).has("status")).isFalse()
        val documentId = idOf(document.body)
        val kind =
            jdbc.queryForObject(
                "SELECT kind FROM documents WHERE id = ?",
                String::class.java,
                UUID.fromString(documentId),
            )
        assertThat(kind).isEqualTo("RECEIPT")

        val replay = post("/documents", key, documentBody)
        assertThat(replay.status).isEqualTo(201)
        assertThat(replay.body).isEqualTo(document.body)
        val documentCount =
            jdbc.queryForObject(
                "SELECT count(*) FROM documents WHERE card_owner_id = ?",
                Long::class.java,
                owner,
            )
        assertThat(documentCount).isEqualTo(1)

        val invoice = post("/documents", uniqueKey(), documentBody(owner = uniqueOwner(), kind = "INVOICE"))
        assertThat(invoice.status).isEqualTo(400)
    }

    @Test
    fun `a committed key with no response is 409 or 422`() {
        val key = uniqueKey()
        val owner = uniqueOwner()
        val body = transactionBody(owner = owner, amount = "6.00")
        jdbc.update(
            """
            INSERT INTO idempotency_keys (key, endpoint, request_hash)
            VALUES (?, ?, ?)
            """.trimIndent(),
            key,
            IdempotencyEndpoints.TRANSACTIONS,
            requestSha256(body.toByteArray(StandardCharsets.UTF_8)),
        )

        val same = post("/transactions", key, body)
        assertThat(same.status).isEqualTo(409)
        assertThat(same.header("Retry-After")).isEqualTo(properties.idempotency.retryAfter.toSeconds().toString())
        assertThat(same.header("Content-Type")).contains("application/problem+json")
        assertThat(same.body).contains("in flight")

        val different = post("/transactions", key, transactionBody(owner = owner, amount = "6.50"))
        assertThat(different.status).isEqualTo(422)
        assertThat(countTransactions(owner)).isZero()
    }

    @Test
    fun `a key held by an open transaction returns 409 with Retry-After`() {
        val key = uniqueKey()
        val owner = uniqueOwner()
        val body = transactionBody(owner = owner, amount = "4.00")
        DriverManager
            .getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
            .use { connection ->
                connection.autoCommit = false
                connection
                    .prepareStatement(
                        """
                        INSERT INTO idempotency_keys (key, endpoint, request_hash)
                        VALUES (?, ?, 'held-open')
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, key)
                        statement.setString(2, IdempotencyEndpoints.TRANSACTIONS)
                        statement.executeUpdate()
                    }
                val started = System.nanoTime()
                val response = post("/transactions", key, body)
                val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                assertThat(response.status).isEqualTo(409)
                assertThat(response.header("Retry-After")).isEqualTo("1")
                assertThat(response.body).contains("in flight")
                assertThat(elapsedMs).isGreaterThanOrEqualTo(properties.idempotency.lockTimeout.toMillis() - 1_000)
                assertThat(countTransactions(owner)).isZero()
                connection.rollback()
            }

        val created = post("/transactions", key, body)
        assertThat(created.status).isEqualTo(201)
        assertThat(countTransactions(owner)).isEqualTo(1)
    }

    @Test
    fun `ten parallel posts with one idempotency key create one row and nine replays`() {
        val key = uniqueKey()
        val owner = uniqueOwner()
        val body = transactionBody(owner = owner, merchant = "Parallel Cafe", amount = "10.00")
        val before = meterRegistry.get("idempotent_replays").counter().count()
        val pool = Executors.newFixedThreadPool(10)
        try {
            val calls =
                (1..10).map {
                    pool.submit<HttpResult> { post("/transactions", key, body) }
                }
            val results = calls.map { it.get(30, TimeUnit.SECONDS) }
            assertThat(results.map { it.status to it.body })
                .allSatisfy { (status, responseBody) ->
                    assertThat(status).isEqualTo(201)
                    assertThat(responseBody).isNotBlank()
                }
            assertThat(results.map { it.body }.toSet()).containsExactly(results.first().body)
            assertThat(countTransactions(owner)).isEqualTo(1)
            val stored = storedTransaction(idOf(results.first().body))
            assertThat(stored.minor).isEqualTo(1000)
            assertThat(stored.currency).isEqualTo("AUD")
            assertThat(meterRegistry.get("idempotent_replays").counter().count() - before).isEqualTo(9.0)

            val scrape = get("/actuator/prometheus")
            assertThat(scrape.status).isEqualTo(200)
            assertThat(scrape.body.lines().any { line -> line.startsWith("idempotent_replays_total ") }).isTrue()
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `openapi lists the ingest routes`() {
        val docs = get("/v3/api-docs")
        assertThat(docs.status).isEqualTo(200)
        assertThat(docs.body).contains("\"/transactions\"").contains("\"/documents\"").contains("X-API-Key")
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

    private fun send(request: HttpRequest): HttpResult {
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        return HttpResult(response.statusCode(), response.body(), response.headers())
    }

    private fun headerKey(result: HttpResult): String = result.sentKey ?: error("request had no idempotency key")

    private fun idOf(body: String): String = json.readTree(body)["id"].asText()

    private fun countTransactions(owner: String): Long =
        jdbc.queryForObject(
            "SELECT count(*) FROM card_transactions WHERE card_owner_id = ?",
            Long::class.java,
            owner,
        )!!

    private fun storedTransaction(id: String): StoredMoney =
        jdbc.queryForObject(
            """
            SELECT amount_minor, currency, tax_minor, merchant_norm, status
            FROM card_transactions
            WHERE id = ?
            """.trimIndent(),
            { rs, _ ->
                StoredMoney(
                    minor = rs.getLong("amount_minor"),
                    currency = rs.getString("currency").trim(),
                    tax = rs.getObject("tax_minor")?.let { (it as Number).toLong() },
                    norm = rs.getString("merchant_norm"),
                    status = rs.getString("status"),
                )
            },
            UUID.fromString(id),
        )!!

    private fun transactionBody(
        owner: String,
        merchant: String = "Cafe",
        amount: String = "12.34",
        currency: String = "AUD",
        date: String = "2026-11-21",
        tax: String? = null,
    ): String {
        val taxJson = if (tax == null) "" else ""","tax":"$tax""""
        return """{"cardOwnerId":"$owner","merchant":"$merchant","amount":"$amount","currency":"$currency","txnDate":"$date"$taxJson}"""
    }

    private fun documentBody(
        owner: String,
        merchant: String = "Cafe",
        amount: String = "12.34",
        currency: String = "AUD",
        date: String = "2026-11-21",
        kind: String = "RECEIPT",
    ): String =
        """{"kind":"$kind","cardOwnerId":"$owner","merchant":"$merchant","amount":"$amount","currency":"$currency","docDate":"$date"}"""

    private fun uniqueOwner(): String = "owner-${UUID.randomUUID()}"

    private fun uniqueKey(): String = UUID.randomUUID().toString()

    private data class StoredMoney(
        val minor: Long,
        val currency: String,
        val tax: Long?,
        val norm: String,
        val status: String,
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
