package com.hhok.spendrecon

import com.hhok.spendrecon.application.AccountingExportPort
import com.hhok.spendrecon.application.OutboxEvent
import com.hhok.spendrecon.application.OutboxPublisher
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(OutboxDeliveryRetryTest.Ports::class)
class OutboxDeliveryRetryTest : PostgresIntegrationTest() {
    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var publisher: OutboxPublisher

    @Autowired
    private lateinit var export: ThrowOnceExport

    @Test
    fun `a publish that throws leaves the row unpublished and the next poll delivers it`() {
        jdbc.update("DELETE FROM outbox WHERE published_at IS NULL")
        val id = insertOutbox()

        assertThatThrownBy { publisher.publishPending() }.isInstanceOf(RuntimeException::class.java)

        assertThat(export.callsFor(id)).isEqualTo(1)
        assertThat(published(id)).isFalse()

        publisher.publishPending()

        assertThat(export.callsFor(id)).isEqualTo(2)
        assertThat(published(id)).isTrue()
    }

    private fun insertOutbox(): Long =
        jdbc.queryForObject(
            """
            INSERT INTO outbox (aggregate_id, event_type, payload)
            VALUES (?, 'MatchConfirmed', '{}'::jsonb)
            RETURNING id
            """.trimIndent(),
            Long::class.java,
            UUID.randomUUID(),
        )!!

    private fun published(id: Long): Boolean =
        jdbc.queryForObject(
            "SELECT published_at IS NOT NULL FROM outbox WHERE id = ?",
            Boolean::class.java,
            id,
        )!!

    @TestConfiguration
    class Ports {
        @Bean
        @Primary
        fun accountingExportPort(): ThrowOnceExport = ThrowOnceExport()
    }
}

class ThrowOnceExport : AccountingExportPort {
    private val calls = ConcurrentHashMap<Long, AtomicInteger>()

    override fun publish(event: OutboxEvent) {
        val attempt = calls.computeIfAbsent(event.id) { AtomicInteger() }.incrementAndGet()
        if (attempt == 1) {
            throw RuntimeException("export failed after recording ${event.id}")
        }
    }

    fun callsFor(id: Long): Int = calls[id]?.get() ?: 0
}
