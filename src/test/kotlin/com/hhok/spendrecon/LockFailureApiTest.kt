@file:Suppress("DEPRECATION")

package com.hhok.spendrecon

import com.fasterxml.jackson.databind.ObjectMapper
import com.hhok.spendrecon.application.ApplyDecision
import com.hhok.spendrecon.application.AppliedDecision
import com.hhok.spendrecon.application.DecisionStore
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.dao.CannotAcquireLockException
import org.springframework.dao.DeadlockLoserDataAccessException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * The idempotency store turns its own lock_timeout into IdempotencyInFlightException.
 * These tests throw the lock exceptions from the decision store, which is the path
 * that reaches lockNotAcquired.
 *
 * DeadlockLoserDataAccessException is deprecated since Spring Framework 6.0.3.
 * A database deadlock is still translated to that class, and it is still a
 * PessimisticLockingFailureException.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
@Import(LockFailureApiTest.FailingDecisionStore::class)
class LockFailureApiTest : PostgresIntegrationTest() {
    @org.springframework.beans.factory.annotation.Autowired
    private lateinit var properties: ReconProperties

    @org.springframework.beans.factory.annotation.Autowired
    private lateinit var meterRegistry: MeterRegistry

    @org.springframework.boot.test.web.server.LocalServerPort
    private var port: Int = 0

    private val json = ObjectMapper()

    @Test
    fun `a CannotAcquireLockException is 409 problem json with Retry-After`() {
        failure.set(CannotAcquireLockException("could not obtain lock"))
        assertLockFailure()
    }

    @Test
    fun `a DeadlockLoserDataAccessException is 409 problem json with Retry-After`() {
        failure.set(DeadlockLoserDataAccessException("deadlock detected", RuntimeException("40P01")))
        assertLockFailure()
    }

    private fun assertLockFailure() {
        val before = conflicts()
        val result =
            post(
                "/matches/${UUID.randomUUID()}/approve",
                UUID.randomUUID().toString(),
                """{"version":0,"actor":"sam"}""",
            )
        val problem = json.readTree(result.body)
        assertThat(result.status).isEqualTo(409)
        assertThat(problem["type"].asText()).isEqualTo("about:blank")
        assertThat(problem["title"].asText()).isEqualTo("Conflict")
        assertThat(problem["status"].asInt()).isEqualTo(409)
        assertThat(problem["detail"].asText()).isEqualTo("The row is busy. Retry the request.")
        assertThat(result.header("Content-Type")).contains("application/problem+json")
        assertThat(result.header("Retry-After")).isEqualTo(properties.idempotency.retryAfter.toSeconds().toString())
        assertThat(conflicts() - before).isEqualTo(0.0)
    }

    private fun conflicts(): Double = meterRegistry.find("optimistic_lock_conflicts").counter()?.count() ?: 0.0

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
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        return HttpResult(response.statusCode(), response.body(), response.headers())
    }

    private class HttpResult(
        val status: Int,
        val body: String,
        private val headers: java.net.http.HttpHeaders,
    ) {
        fun header(name: String): String? = headers.firstValue(name).orElse(null)
    }

    @TestConfiguration
    class FailingDecisionStore {
        @Bean
        @Primary
        fun failingDecisionStore(): DecisionStore =
            object : DecisionStore {
                override fun apply(command: ApplyDecision): AppliedDecision = throw failure.get()
            }
    }

    companion object {
        val failure: AtomicReference<RuntimeException> =
            AtomicReference(CannotAcquireLockException("could not obtain lock"))
    }
}
