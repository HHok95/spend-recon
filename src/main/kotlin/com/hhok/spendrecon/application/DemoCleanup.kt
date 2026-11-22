package com.hhok.spendrecon.application

import com.hhok.spendrecon.SpendReconProperties
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.SchedulingConfigurer
import org.springframework.scheduling.config.ScheduledTaskRegistrar
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Connection
import java.util.UUID

@Service
class DemoCleanup(
    private val jdbc: JdbcTemplate,
) {
    /**
     * Deletes every expired tenant this transaction can lock.
     * SKIP LOCKED makes a second caller leave those rows for the first caller.
     * Running it again after they are gone deletes nothing.
     */
    @Transactional
    fun purgeExpired(): Int {
        jdbc.execute("SET LOCAL spendrecon.purge = 'on'")
        val ids =
            jdbc.query(
                """
                SELECT id
                FROM tenants
                WHERE expires_at IS NOT NULL
                  AND expires_at <= now()
                ORDER BY expires_at, id
                FOR UPDATE SKIP LOCKED
                """.trimIndent(),
            ) { rs, _ -> rs.getObject("id", UUID::class.java) }
        if (ids.isEmpty()) {
            return 0
        }
        delete(
            """
            DELETE FROM reconciliation_events
            WHERE match_id IN (SELECT id FROM matches WHERE tenant_id = ANY (?))
            """.trimIndent(),
            ids,
        )
        delete(
            """
            DELETE FROM outbox
            WHERE aggregate_id IN (SELECT id FROM matches WHERE tenant_id = ANY (?))
            """.trimIndent(),
            ids,
        )
        delete("DELETE FROM matches WHERE tenant_id = ANY (?)", ids)
        delete("DELETE FROM idempotency_keys WHERE tenant_id = ANY (?)", ids)
        delete("DELETE FROM documents WHERE tenant_id = ANY (?)", ids)
        delete("DELETE FROM card_transactions WHERE tenant_id = ANY (?)", ids)
        delete("DELETE FROM demo_sessions WHERE tenant_id = ANY (?)", ids)
        delete("DELETE FROM tenants WHERE id = ANY (?)", ids)
        return ids.size
    }

    private fun delete(
        sql: String,
        ids: List<UUID>,
    ) {
        jdbc.execute { connection: Connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setArray(1, connection.createArrayOf("uuid", ids.toTypedArray()))
                statement.executeUpdate()
            }
        }
    }
}

@Configuration
@ConditionalOnProperty(prefix = "spendrecon.demo", name = ["enabled"], havingValue = "true")
class DemoCleanupSchedule(
    private val cleanup: DemoCleanup,
    private val properties: SpendReconProperties,
) : SchedulingConfigurer {
    override fun configureTasks(taskRegistrar: ScheduledTaskRegistrar) {
        taskRegistrar.addFixedDelayTask(
            Runnable { cleanup.purgeExpired() },
            properties.demo.cleanupInterval,
        )
    }
}
