package com.hhok.spendrecon

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.SQLException
import java.util.UUID

@SpringBootTest
class MatchScoreConstraintTest : PostgresIntegrationTest() {
    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Test
    fun `a receipt requires a score, a missing receipt forbids one, and the score stays in range`() {
        val bare = insertTransaction()
        assertCheckViolation {
            jdbc.update(
                """
                INSERT INTO matches (id, transaction_id, document_id, status, score)
                VALUES (?, ?, ?, 'EXCEPTION', NULL)
                """.trimIndent(),
                UUID.randomUUID(),
                bare,
                insertDocument(),
            )
        }

        val scoredWithoutReceipt = insertTransaction()
        assertCheckViolation {
            jdbc.update(
                """
                INSERT INTO matches (id, transaction_id, status, score)
                VALUES (?, ?, 'EXCEPTION', 0.500)
                """.trimIndent(),
                UUID.randomUUID(),
                scoredWithoutReceipt,
            )
        }

        val tooHigh = insertTransaction()
        assertCheckViolation {
            jdbc.update(
                """
                INSERT INTO matches (id, transaction_id, document_id, status, score)
                VALUES (?, ?, ?, 'MATCHED', 1.200)
                """.trimIndent(),
                UUID.randomUUID(),
                tooHigh,
                insertDocument(),
            )
        }

        val open = insertTransaction()
        jdbc.update(
            """
            INSERT INTO matches (id, transaction_id, status, score, exception_type)
            VALUES (?, ?, 'EXCEPTION', NULL, 'NO_CANDIDATE')
            """.trimIndent(),
            UUID.randomUUID(),
            open,
        )
        val stored =
            jdbc.query(
                "SELECT score FROM matches WHERE transaction_id = ?",
                { rs, _ -> rs.getBigDecimal("score") },
                open,
            ).single()
        assertThat(stored).isNull()
    }

    @Test
    fun `two exception rows cannot hold the same receipt`() {
        val documentId = insertDocument()
        val first = insertTransaction()
        val second = insertTransaction()
        jdbc.update(
            """
            INSERT INTO matches (id, transaction_id, document_id, status, score, exception_type)
            VALUES (?, ?, ?, 'EXCEPTION', 0.500, 'AMOUNT_MISMATCH')
            """.trimIndent(),
            UUID.randomUUID(),
            first,
            documentId,
        )

        assertThatThrownBy {
            jdbc.update(
                """
                INSERT INTO matches (id, transaction_id, document_id, status, score, exception_type)
                VALUES (?, ?, ?, 'EXCEPTION', 0.400, 'AMOUNT_MISMATCH')
                """.trimIndent(),
                UUID.randomUUID(),
                second,
                documentId,
            )
        }.satisfies({ thrown ->
            assertThat(sqlState(thrown)).isEqualTo("23505")
        })
    }

    private fun assertCheckViolation(insert: () -> Unit) {
        assertThatThrownBy { insert() }.satisfies({ thrown ->
            assertThat(sqlState(thrown)).isEqualTo("23514")
        })
    }

    private fun insertTransaction(): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO card_transactions (
                id, card_owner_id, merchant_raw, merchant_norm,
                amount_minor, currency, txn_date, status
            ) VALUES (?, 'owner-1', 'Cafe', 'cafe', 1234, 'AUD', DATE '2026-11-21', 'UNMATCHED')
            """.trimIndent(),
            id,
        )
        return id
    }

    private fun insertDocument(): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO documents (
                id, kind, card_owner_id, merchant_raw, merchant_norm,
                amount_minor, currency, doc_date
            ) VALUES (?, 'RECEIPT', 'owner-1', 'Cafe', 'cafe', 1234, 'AUD', DATE '2026-11-21')
            """.trimIndent(),
            id,
        )
        return id
    }
}

private fun sqlState(error: Throwable): String? {
    var current: Throwable? = error
    while (current != null) {
        if (current is SQLException && !current.sqlState.isNullOrBlank()) {
            return current.sqlState
        }
        current = current.cause
    }
    return null
}
