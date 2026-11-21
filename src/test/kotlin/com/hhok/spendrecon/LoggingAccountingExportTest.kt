package com.hhok.spendrecon

import com.hhok.spendrecon.adapters.out.LoggingAccountingExport
import com.hhok.spendrecon.application.OutboxEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class LoggingAccountingExportTest {
    @Test
    fun `a second delivery of the same outbox id is not accepted again`() {
        val export = LoggingAccountingExport()
        val event = OutboxEvent(id = 7, aggregateId = UUID.randomUUID(), eventType = "MatchConfirmed", payload = "{}")
        export.publish(event)
        export.publish(event)
        assertThat(export.callsFor(7)).isEqualTo(2)
        assertThat(export.acceptedCount()).isEqualTo(1)
        assertThat(export.hasAccepted(7)).isTrue()
    }
}
