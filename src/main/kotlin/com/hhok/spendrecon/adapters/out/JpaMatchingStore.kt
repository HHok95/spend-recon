package com.hhok.spendrecon.adapters.out

import com.fasterxml.jackson.databind.ObjectMapper
import com.hhok.spendrecon.application.MatchingStore
import com.hhok.spendrecon.application.OUTBOX_MATCH_CONFIRMED
import com.hhok.spendrecon.application.SaveMatch
import com.hhok.spendrecon.application.SavedMatch
import com.hhok.spendrecon.domain.ChargeView
import com.hhok.spendrecon.domain.ReceiptView
import jakarta.persistence.EntityManager
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JpaMatchingStore(
    private val jdbc: JdbcTemplate,
    private val entityManager: EntityManager,
    private val json: ObjectMapper,
) : MatchingStore {
    override fun lockCharge(transactionId: UUID): ChargeView? =
        jdbc
            .query(
                """
                SELECT card_owner_id, merchant_norm, amount_minor, currency, tax_minor, txn_date
                FROM card_transactions
                WHERE id = ?
                FOR UPDATE
                """.trimIndent(),
                { rs, _ -> rs.toCharge() },
                transactionId,
            ).firstOrNull()

    // Every receipt is locked, in id order, after the charge lock. The second
    // reconcile waits, then sees the receipt the first one stored.
    override fun lockReceipts(): List<ReceiptView> =
        jdbc.query(
            """
            SELECT id, card_owner_id, merchant_norm, amount_minor, currency, tax_minor, doc_date
            FROM documents
            WHERE kind = 'RECEIPT'
            ORDER BY id
            FOR UPDATE
            """.trimIndent(),
        ) { rs, _ -> rs.toReceipt() }

    override fun heldDocumentIds(): Set<UUID> =
        jdbc
            .query(
                """
                SELECT document_id
                FROM matches
                WHERE document_id IS NOT NULL
                  AND status <> 'REJECTED'
                """.trimIndent(),
            ) { rs, _ -> rs.getObject("document_id", UUID::class.java) }
            .toSet()

    override fun hasMatch(transactionId: UUID): Boolean =
        jdbc.queryForObject(
            "SELECT count(*) FROM matches WHERE transaction_id = ?",
            Long::class.java,
            transactionId,
        ) != 0L

    override fun save(save: SaveMatch): SavedMatch {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val candidate = save.decision.candidate
        entityManager.persist(
            MatchEntity(
                id = save.matchId,
                transactionId = save.transactionId,
                documentId = candidate?.documentId,
                status = save.finalStatus.name,
                score = candidate?.score,
                rulesFired = save.decision.rulesFired.map { it.name }.toMutableList(),
                exceptionType = save.decision.exceptionType?.name,
                version = 0,
                decidedBy = save.decidedBy,
                decidedAt = if (save.decidedBy == null) null else now,
                createdAt = now,
            ),
        )
        entityManager.flush()
        save.events.forEach { event ->
            entityManager.persist(
                ReconciliationEventEntity(
                    matchId = save.matchId,
                    eventType = event.eventType,
                    actor = event.actor,
                    rulesFired = event.rulesFired.toMutableList(),
                    beforeState = event.beforeJson?.let { json.readTree(it) },
                    afterState = event.afterJson?.let { json.readTree(it) },
                    createdAt = now,
                ),
            )
        }
        entityManager.flush()
        if (save.outboxPayload != null) {
            jdbc.update(
                """
                INSERT INTO outbox (aggregate_id, event_type, payload)
                VALUES (?, ?, ?::jsonb)
                """.trimIndent(),
                save.matchId,
                OUTBOX_MATCH_CONFIRMED,
                save.outboxPayload,
            )
        }
        jdbc.update(
            "UPDATE card_transactions SET status = ? WHERE id = ?",
            save.finalStatus.name,
            save.transactionId,
        )
        return SavedMatch(save.matchId, version = 0, finalStatus = save.finalStatus)
    }
}

private fun ResultSet.toCharge(): ChargeView =
    ChargeView(
        ownerId = getString("card_owner_id"),
        merchantNorm = getString("merchant_norm"),
        amountMinor = getLong("amount_minor"),
        currency = getString("currency").trim(),
        taxMinor = optionalLong("tax_minor"),
        txnDate = getObject("txn_date", LocalDate::class.java),
    )

private fun ResultSet.toReceipt(): ReceiptView =
    ReceiptView(
        documentId = getObject("id", UUID::class.java),
        ownerId = getString("card_owner_id"),
        merchantNorm = getString("merchant_norm"),
        amountMinor = getLong("amount_minor"),
        currency = getString("currency").trim(),
        taxMinor = optionalLong("tax_minor"),
        docDate = getObject("doc_date", LocalDate::class.java),
    )

private fun ResultSet.optionalLong(column: String): Long? {
    val value = getLong(column)
    return if (wasNull()) null else value
}
