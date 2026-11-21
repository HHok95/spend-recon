package com.hhok.spendrecon

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

@SpringBootTest
class ReconciliationEventsAppendOnlyTest : PostgresIntegrationTest() {
    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Test
    fun `update and delete on reconciliation events fail`() {
        val transactionId = UUID.randomUUID()
        val matchId = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO card_transactions (
                id, card_owner_id, merchant_raw, merchant_norm,
                amount_minor, currency, txn_date, status
            ) VALUES (?, 'owner-1', 'Cafe', 'cafe', 1234, 'AUD', DATE '2026-11-21', 'UNMATCHED')
            """.trimIndent(),
            transactionId,
        )
        jdbc.update(
            """
            INSERT INTO matches (id, transaction_id, status, score, exception_type)
            VALUES (?, ?, 'EXCEPTION', NULL, 'NO_CANDIDATE')
            """.trimIndent(),
            matchId,
            transactionId,
        )
        jdbc.update(
            """
            INSERT INTO reconciliation_events (match_id, event_type, actor, reason)
            VALUES (?, 'MATCHED', 'tester', 'created')
            """.trimIndent(),
            matchId,
        )
        val eventId =
            jdbc.queryForObject(
                "SELECT id FROM reconciliation_events WHERE match_id = ?",
                Long::class.java,
                matchId,
            )

        assertThatThrownBy {
            jdbc.update(
                "UPDATE reconciliation_events SET reason = 'changed' WHERE id = ?",
                eventId,
            )
        }.hasMessageContaining("append-only")

        assertThatThrownBy {
            jdbc.update("DELETE FROM reconciliation_events WHERE id = ?", eventId)
        }.hasMessageContaining("append-only")

        val reason =
            jdbc.queryForObject(
                "SELECT reason FROM reconciliation_events WHERE id = ?",
                String::class.java,
                eventId,
            )
        assertThat(reason).isEqualTo("created")
    }
}
