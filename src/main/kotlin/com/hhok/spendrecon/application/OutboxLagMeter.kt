package com.hhok.spendrecon.application

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/** Age of the oldest unpublished outbox row. Zero when nothing is waiting. */
@Component
class OutboxLagMeter(
    registry: MeterRegistry,
    private val jdbc: JdbcTemplate,
) {
    init {
        Gauge
            .builder("outbox_lag_seconds", this, OutboxLagMeter::lagSeconds)
            .description("Age in seconds of the oldest unpublished outbox row. Zero when the queue is empty.")
            .register(registry)
    }

    fun lagSeconds(): Double {
        val seconds =
            jdbc
                .query(
                    """
                    SELECT EXTRACT(EPOCH FROM (clock_timestamp() - min(created_at)))
                    FROM outbox
                    WHERE published_at IS NULL
                    """.trimIndent(),
                ) { rs, _ ->
                    val value = rs.getBigDecimal(1)
                    if (rs.wasNull() || value == null) 0.0 else value.toDouble()
                }.single()
        return seconds.coerceAtLeast(0.0)
    }
}
