package com.hhok.spendrecon

import com.hhok.spendrecon.adapters.out.LoggingAccountingExport
import com.hhok.spendrecon.application.OutboxPublisher
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OutboxPublisherTest : PostgresIntegrationTest() {
    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var publisher: OutboxPublisher

    @Autowired
    private lateinit var export: LoggingAccountingExport

    @Autowired
    private lateinit var meterRegistry: MeterRegistry

    @Test
    fun `a row committed while the poller is off is published when the poller starts`() {
        val id = insertOutbox(age = "0 seconds")
        assertThat(published(id)).isFalse()
        assertThat(export.callsFor(id)).isZero()

        publisher.publishPending()

        assertThat(published(id)).isTrue()
        assertThat(export.callsFor(id)).isEqualTo(1)
        assertThat(export.hasAccepted(id)).isTrue()

        publisher.publishPending()

        assertThat(export.callsFor(id)).isEqualTo(1)
    }

    @Test
    fun `lag is the age of the oldest unpublished row and drops to zero when the queue is empty`() {
        insertOutbox(age = "2 hours")
        val lag = meterRegistry.get("outbox_lag_seconds").gauge().value()
        assertThat(lag).isGreaterThanOrEqualTo(3600.0)

        repeat(20) {
            if (unpublished() == 0L) {
                return@repeat
            }
            publisher.publishPending()
        }

        assertThat(unpublished()).isZero()
        assertThat(meterRegistry.get("outbox_lag_seconds").gauge().value()).isEqualTo(0.0)
    }

    private fun insertOutbox(age: String): Long =
        jdbc.queryForObject(
            """
            INSERT INTO outbox (aggregate_id, event_type, payload, created_at)
            VALUES (?, 'MatchConfirmed', '{"source":"test"}'::jsonb, clock_timestamp() - (?::interval))
            RETURNING id
            """.trimIndent(),
            Long::class.java,
            UUID.randomUUID(),
            age,
        )!!

    private fun published(id: Long): Boolean =
        jdbc.queryForObject(
            "SELECT published_at IS NOT NULL FROM outbox WHERE id = ?",
            Boolean::class.java,
            id,
        )!!

    private fun unpublished(): Long =
        jdbc.queryForObject(
            "SELECT count(*) FROM outbox WHERE published_at IS NULL",
            Long::class.java,
        )!!
}
