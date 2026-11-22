package com.hhok.spendrecon

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.hhok.spendrecon.adapters.`in`.DemoRateLimiter
import com.hhok.spendrecon.application.DemoCleanup
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.DriverManager
import java.time.Duration
import java.util.UUID

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spendrecon.demo.enabled=true",
        "spendrecon.demo.cleanup-interval=1h",
        "spendrecon.cors.allowed-origin-patterns[0]=https://spend.hokheng.com",
        "spendrecon.cors.allowed-origin-patterns[1]=http://localhost:*",
        "spendrecon.cors.allowed-origin-patterns[2]=http://127.0.0.1:*",
    ],
)
class DemoApiTest : PostgresIntegrationTest() {
    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var cleanup: DemoCleanup

    @Autowired
    private lateinit var limiter: DemoRateLimiter

    @LocalServerPort
    private var port: Int = 0

    private val http: HttpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    private val json = ObjectMapper()

    @BeforeEach
    fun resetLimiter() {
        limiter.clear()
    }

    @Test
    fun `a session is seeded and the bearer token drives reconcile approve export and history`() {
        val opened = openSession()
        val token = opened["token"].asText()
        val missing = get("/transactions", token = null)
        assertThat(missing.statusCode()).isEqualTo(401)
        assertThat(missing.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json")

        val keyOnly =
            post(
                "/transactions",
                body =
                    """
                    {"cardOwnerId":"visitor","merchant":"Nope","amount":"1.00","currency":"AUD","txnDate":"2026-11-18"}
                    """.trimIndent(),
                token = null,
                key = uniqueKey(),
                apiKey = "local-dev-key",
            )
        assertThat(keyOnly.statusCode()).isEqualTo(401)

        val charges = get("/transactions", token)
        assertThat(charges.statusCode()).isEqualTo(200)
        val content = json.readTree(charges.body())["content"]
        assertThat(content).hasSize(3)
        assertThat(content.map { it["merchant"].asText() }).contains("Northwind Coffee", "City Books", "Tram Fare")

        val documents = get("/documents", token)
        assertThat(documents.statusCode()).isEqualTo(200)
        assertThat(json.readTree(documents.body())["content"]).hasSize(3)

        val coffee = content.first { it["merchant"].asText() == "Northwind Coffee" }
        assertThat(coffee["amount"].asText()).isEqualTo("18.40")
        assertThat(coffee["status"].asText()).isEqualTo("UNMATCHED")
        val reconciled = post("/reconcile/${coffee["id"].asText()}", body = null, token = token, key = uniqueKey())
        assertThat(reconciled.statusCode()).isEqualTo(200)
        val match = json.readTree(reconciled.body())
        assertThat(match["status"].asText()).isEqualTo("APPROVED")
        assertThat(match["score"].decimalValue()).isEqualByComparingTo("1.000")

        val books = content.first { it["merchant"].asText() == "City Books" }
        val exception = post("/reconcile/${books["id"].asText()}", body = null, token = token, key = uniqueKey())
        assertThat(exception.statusCode()).isEqualTo(200)
        val exceptionJson = json.readTree(exception.body())
        assertThat(exceptionJson["status"].asText()).isEqualTo("EXCEPTION")
        val approved =
            post(
                "/matches/${exceptionJson["matchId"].asText()}/approve",
                body = """{"version":0,"actor":"visitor","reason":"same purchase"}""",
                token = token,
                key = uniqueKey(),
            )
        assertThat(approved.statusCode()).isEqualTo(200)

        val exported = get("/demo/export", token)
        assertThat(exported.statusCode()).isEqualTo(200)
        assertThat(exported.headers().firstValue("Content-Type").orElse("")).contains("text/csv")
        assertThat(exported.body()).contains("matchId,transactionId,documentId,status,score,merchant,amount,currency,txnDate,decidedBy,decidedAt")
        assertThat(exported.body()).contains(coffee["id"].asText())
        assertThat(exported.body()).contains("Northwind Coffee")
        assertThat(exported.body()).contains("Tram Fare")
        assertThat(exported.body()).contains("City Books")

        val history = get("/transactions/${coffee["id"].asText()}/history", token)
        assertThat(history.statusCode()).isEqualTo(200)
        val events = json.readTree(history.body()).map { it["eventType"].asText() }
        assertThat(events).contains("MATCHED", "APPROVED")

        val tram = content.first { it["merchant"].asText() == "Tram Fare" }
        val tramHistory = json.readTree(get("/transactions/${tram["id"].asText()}/history", token).body())
        assertThat(tramHistory.map { it["eventType"].asText() }).containsExactly("MATCHED", "APPROVED")
    }

    @Test
    fun `one tenant cannot read another tenant's charge`() {
        val first = openSession()
        val second = openSession()
        val created =
            post(
                "/transactions",
                body =
                    """
                    {"cardOwnerId":"visitor","merchant":"Secret Cafe","amount":"9.50","currency":"AUD","txnDate":"2026-11-18","tax":"0.86"}
                    """.trimIndent(),
                token = first["token"].asText(),
                key = uniqueKey(),
            )
        assertThat(created.statusCode()).isEqualTo(201)
        val id = json.readTree(created.body())["id"].asText()

        val hidden = get("/transactions/$id", second["token"].asText())
        assertThat(hidden.statusCode()).isEqualTo(404)
        val list = json.readTree(get("/transactions", second["token"].asText()).body())["content"]
        assertThat(list.map { it["id"].asText() }).doesNotContain(id)

        val visible = get("/transactions/$id", first["token"].asText())
        assertThat(visible.statusCode()).isEqualTo(200)
        assertThat(json.readTree(visible.body())["merchant"].asText()).isEqualTo("Secret Cafe")

        val foreignReconcile = post("/reconcile/$id", body = null, token = second["token"].asText(), key = uniqueKey())
        assertThat(foreignReconcile.statusCode()).isEqualTo(404)
    }

    @Test
    fun `cleanup deletes an expired tenant, skips a locked one, and leaves a live tenant`() {
        val expired = openSession()
        val live = openSession()
        val expiredId = UUID.fromString(expired["tenantId"].asText())
        val liveId = UUID.fromString(live["tenantId"].asText())
        jdbc.update("UPDATE tenants SET expires_at = now() - interval '1 minute' WHERE id = ?", expiredId)
        jdbc.update("UPDATE demo_sessions SET expires_at = now() - interval '1 minute' WHERE tenant_id = ?", expiredId)

        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.autoCommit = false
            connection.prepareStatement("SELECT id FROM tenants WHERE id = ? FOR UPDATE").use { statement ->
                statement.setObject(1, expiredId)
                statement.executeQuery().use { rs -> assertThat(rs.next()).isTrue() }
            }
            cleanup.purgeExpired()
            assertThat(chargeCount(expiredId)).isEqualTo(3)
            connection.rollback()
        }

        val removed = cleanup.purgeExpired()
        assertThat(removed).isGreaterThanOrEqualTo(1)
        assertThat(chargeCount(expiredId)).isZero()
        assertThat(eventCount(expiredId)).isZero()
        assertThat(tenantExists(expiredId)).isFalse()
        assertThat(chargeCount(liveId)).isEqualTo(3)
        assertThat(eventCount(liveId)).isEqualTo(2)
        assertThat(tenantExists(Tenants.LOCAL)).isTrue()

        assertThat(cleanup.purgeExpired()).isZero()
        assertThat(chargeCount(liveId)).isEqualTo(3)

        val expiredToken = get("/transactions", expired["token"].asText())
        assertThat(expiredToken.statusCode()).isEqualTo(401)
        assertThat(get("/transactions", live["token"].asText()).statusCode()).isEqualTo(200)
    }

    @Test
    fun `preflight allows the spend origin and rejects another origin`() {
        val allowed = options("https://spend.hokheng.com")
        assertThat(allowed.statusCode()).isEqualTo(200)
        assertThat(allowed.headers().firstValue("Access-Control-Allow-Origin").orElse(""))
            .isEqualTo("https://spend.hokheng.com")

        assertThat(allowed.headers().firstValue("Access-Control-Expose-Headers").orElse(""))
            .contains("Retry-After")
        assertThat(allowed.headers().firstValue("Access-Control-Allow-Headers").orElse(""))
            .containsIgnoringCase("authorization")
            .containsIgnoringCase("idempotency-key")
            .containsIgnoringCase("accept")

        val local = options("http://localhost:3000")
        assertThat(local.statusCode()).isEqualTo(200)
        assertThat(local.headers().firstValue("Access-Control-Allow-Origin").orElse(""))
            .isEqualTo("http://localhost:3000")

        val loopback = options("http://127.0.0.1:3000")
        assertThat(loopback.statusCode()).isEqualTo(200)
        assertThat(loopback.headers().firstValue("Access-Control-Allow-Origin").orElse(""))
            .isEqualTo("http://127.0.0.1:3000")

        val blocked = options("https://example.com")
        assertThat(blocked.statusCode()).isEqualTo(403)
        assertThat(blocked.headers().firstValue("Access-Control-Allow-Origin")).isEmpty()
    }

    private fun openSession(): JsonNode {
        val response = post("/demo/session", body = null, token = null, key = null)
        assertThat(response.statusCode()).isEqualTo(201)
        val body = json.readTree(response.body())
        assertThat(body["tenantId"].asText()).isNotBlank()
        assertThat(body["token"].asText()).isNotBlank()
        assertThat(body["expiresAt"].asText()).isNotBlank()
        return body
    }

    private fun chargeCount(tenantId: UUID): Long =
        jdbc.queryForObject(
            "SELECT count(*) FROM card_transactions WHERE tenant_id = ?",
            Long::class.java,
            tenantId,
        )!!

    private fun eventCount(tenantId: UUID): Long =
        jdbc.queryForObject(
            """
            SELECT count(*)
            FROM reconciliation_events e
            JOIN matches m ON m.id = e.match_id
            WHERE m.tenant_id = ?
            """.trimIndent(),
            Long::class.java,
            tenantId,
        )!!

    private fun tenantExists(tenantId: UUID): Boolean =
        jdbc.queryForObject(
            "SELECT count(*) FROM tenants WHERE id = ?",
            Long::class.java,
            tenantId,
        ) == 1L

    private fun options(origin: String): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:$port/transactions"))
                .timeout(Duration.ofSeconds(10))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", origin)
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "authorization,content-type,idempotency-key,accept")
                .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun get(
        path: String,
        token: String?,
    ): HttpResponse<String> {
        val builder =
            HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:$port$path"))
                .timeout(Duration.ofSeconds(10))
                .GET()
        if (token != null) {
            builder.header("Authorization", "Bearer $token")
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun post(
        path: String,
        body: String?,
        token: String?,
        key: String?,
        apiKey: String? = null,
    ): HttpResponse<String> {
        val builder =
            HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:$port$path"))
                .timeout(Duration.ofSeconds(10))
        if (body == null) {
            builder.POST(HttpRequest.BodyPublishers.noBody())
        } else {
            builder.header("Content-Type", "application/json")
            builder.POST(HttpRequest.BodyPublishers.ofString(body))
        }
        if (token != null) {
            builder.header("Authorization", "Bearer $token")
        }
        if (key != null) {
            builder.header("Idempotency-Key", key)
        }
        if (apiKey != null) {
            builder.header("X-API-Key", apiKey)
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun uniqueKey(): String = "demo-${UUID.randomUUID()}"
}
