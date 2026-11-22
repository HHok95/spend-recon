package com.hhok.spendrecon

import com.hhok.spendrecon.adapters.`in`.DemoRateLimiter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spendrecon.demo.enabled=true",
        "spendrecon.demo.cleanup-interval=1h",
        "spendrecon.demo.session-creates-per-hour=2",
        "spendrecon.demo.requests-per-minute=2",
        "spendrecon.cors.allowed-origin-patterns[0]=https://spend.hokheng.com",
    ],
)
class DemoRateLimitTest : PostgresIntegrationTest() {
    @Autowired
    private lateinit var limiter: DemoRateLimiter

    @LocalServerPort
    private var port: Int = 0

    private val http: HttpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    @BeforeEach
    fun resetLimiter() {
        limiter.clear()
    }

    @Test
    fun `the third session from one address is 429`() {
        assertThat(postSession().statusCode()).isEqualTo(201)
        assertThat(postSession().statusCode()).isEqualTo(201)
        val blocked = postSession(origin = "https://spend.hokheng.com")
        assertThat(blocked.statusCode()).isEqualTo(429)
        assertThat(blocked.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json")
        assertThat(blocked.headers().firstValue("Retry-After").orElse("")).isNotBlank()
        assertThat(blocked.headers().firstValue("Access-Control-Allow-Origin").orElse(""))
            .isEqualTo("https://spend.hokheng.com")
        assertThat(blocked.headers().firstValue("Access-Control-Expose-Headers").orElse("")).contains("Retry-After")
        assertThat(blocked.body()).contains("\"status\":429")
    }

    @Test
    fun `the third call with one token is 429`() {
        val opened = postSession()
        assertThat(opened.statusCode()).isEqualTo(201)
        val token =
            opened
                .body()
                .substringAfter("\"token\":\"")
                .substringBefore('"')
        assertThat(getTransactions(token).statusCode()).isEqualTo(200)
        assertThat(getTransactions(token).statusCode()).isEqualTo(200)
        val blocked = getTransactions(token)
        assertThat(blocked.statusCode()).isEqualTo(429)
        assertThat(blocked.headers().firstValue("Retry-After").orElse("").toLong()).isGreaterThan(0)
        assertThat(blocked.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json")
        assertThat(blocked.body()).contains("\"status\":429")
    }

    private fun postSession(origin: String? = null): HttpResponse<String> {
        val builder =
            HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:$port/demo/session"))
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.noBody())
        if (origin != null) {
            builder.header("Origin", origin)
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun getTransactions(token: String): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:$port/transactions"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer $token")
                .GET()
                .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }
}
