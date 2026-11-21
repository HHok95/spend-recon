package com.hhok.spendrecon

import com.hhok.spendrecon.application.AccountingExportPort
import com.hhok.spendrecon.application.OutboxEvent
import com.hhok.spendrecon.application.OutboxPublisher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "recon.outbox.batch-size=1",
        "recon.outbox.enabled=false",
        "recon.outbox.poll-interval=1s",
    ],
)
@Import(OutboxSkipLockedTest.Ports::class)
class OutboxSkipLockedTest : PostgresIntegrationTest() {
    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var publisher: OutboxPublisher

    @Autowired
    private lateinit var export: BarrierExport

    @Test
    fun `two pollers each publish one row and neither publishes both`() {
        jdbc.update("DELETE FROM outbox WHERE published_at IS NULL")
        val first = insertOutbox()
        val second = insertOutbox()
        val pool = Executors.newFixedThreadPool(2)
        val start = java.util.concurrent.CountDownLatch(1)
        try {
            val runs =
                listOf(first, second).map {
                    pool.submit<Unit> {
                        start.await(10, TimeUnit.SECONDS)
                        publisher.publishPending()
                    }
                }
            start.countDown()
            runs.forEach { it.get(20, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        assertThat(export.ids).containsExactlyInAnyOrder(first, second)
        val stillWaiting =
            jdbc.queryForObject(
                "SELECT count(*) FROM outbox WHERE id IN (?, ?) AND published_at IS NULL",
                Long::class.java,
                first,
                second,
            )
        assertThat(stillWaiting).isZero()
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

    @TestConfiguration
    class Ports {
        @Bean
        @Primary
        fun accountingExportPort(): BarrierExport = BarrierExport()
    }
}

class BarrierExport : AccountingExportPort {
    private val barrier = CyclicBarrier(2)
    val ids = ConcurrentLinkedQueue<Long>()

    override fun publish(event: OutboxEvent) {
        ids.add(event.id)
        barrier.await(10, TimeUnit.SECONDS)
    }
}
