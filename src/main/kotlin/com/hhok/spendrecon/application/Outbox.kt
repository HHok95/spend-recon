package com.hhok.spendrecon.application

import com.hhok.spendrecon.ReconProperties
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * One committed outbox row. Delivery is at least once: a crash after the port
 * accepts the row and before published_at is set presents the same id again.
 * The consumer skips an id it has already accepted.
 */
data class OutboxEvent(
    val id: Long,
    val aggregateId: UUID,
    val eventType: String,
    val payload: String,
)

interface AccountingExportPort {
    fun publish(event: OutboxEvent)
}

@Service
class OutboxPublisher(
    private val jdbc: JdbcTemplate,
    private val port: AccountingExportPort,
    private val properties: ReconProperties,
) {
    /**
     * Locks a batch of unpublished rows, hands each to the port, then sets published_at.
     * SKIP LOCKED lets a second poller take the next rows instead of waiting on this batch.
     * The limit is a config integer, checked again here so it can be interpolated safely.
     */
    @Transactional
    fun publishPending() {
        val limit = properties.outbox.batchSize
        check(limit in 1..50) { "Outbox batch size is not a safe SQL literal." }
        val rows =
            jdbc.query(
                """
                SELECT id, aggregate_id, event_type, payload::text AS payload
                FROM outbox
                WHERE published_at IS NULL
                ORDER BY id
                FOR UPDATE SKIP LOCKED
                LIMIT $limit
                """.trimIndent(),
            ) { rs, _ ->
                OutboxEvent(
                    id = rs.getLong("id"),
                    aggregateId = rs.getObject("aggregate_id", UUID::class.java),
                    eventType = rs.getString("event_type"),
                    payload = rs.getString("payload"),
                )
            }
        if (rows.isEmpty()) {
            return
        }
        val publishedAt = OffsetDateTime.now(ZoneOffset.UTC)
        rows.forEach { event ->
            port.publish(event)
            val updated =
                jdbc.update(
                    "UPDATE outbox SET published_at = ? WHERE id = ?",
                    publishedAt,
                    event.id,
                )
            check(updated == 1) { "Outbox row ${event.id} was not marked published." }
        }
    }
}
