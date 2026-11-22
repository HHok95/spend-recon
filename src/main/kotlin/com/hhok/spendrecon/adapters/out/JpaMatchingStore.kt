package com.hhok.spendrecon.adapters.out

import com.fasterxml.jackson.databind.ObjectMapper
import com.hhok.spendrecon.CurrentTenant
import com.hhok.spendrecon.application.LockedMatch
import com.hhok.spendrecon.application.MatchingStore
import com.hhok.spendrecon.application.OUTBOX_MATCH_CONFIRMED
import com.hhok.spendrecon.application.SaveMatch
import com.hhok.spendrecon.application.SavedMatch
import com.hhok.spendrecon.domain.ChargeView
import com.hhok.spendrecon.domain.MatchStatus
import com.hhok.spendrecon.domain.ReceiptView
import com.hhok.spendrecon.domain.RuleSet
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
    private val tenants: CurrentTenant,
) : MatchingStore {
    override fun lockCharge(transactionId: UUID): ChargeView? =
        jdbc
            .query(
                """
                SELECT card_owner_id, merchant_norm, amount_minor, currency, tax_minor, txn_date
                FROM card_transactions
                WHERE id = ? AND tenant_id = ?
                FOR UPDATE
                """.trimIndent(),
                { rs, _ -> rs.toCharge() },
                transactionId,
                tenants.id(),
            ).firstOrNull()

    // Same owner and the three-day window are already hard filters, so rows
    // outside them cannot change this decision. Locking only those rows, in id
    // order, lets a different owner reconcile at the same time.
    override fun lockReceipts(
        ownerId: String,
        txnDate: LocalDate,
    ): List<ReceiptView> =
        jdbc.query(
            """
            SELECT id, card_owner_id, merchant_norm, amount_minor, currency, tax_minor, doc_date
            FROM documents
            WHERE kind = 'RECEIPT'
              AND tenant_id = ?
              AND card_owner_id = ?
              AND doc_date BETWEEN ? AND ?
            ORDER BY id
            FOR UPDATE
            """.trimIndent(),
            { rs, _ -> rs.toReceipt() },
            tenants.id(),
            ownerId,
            txnDate.minusDays(RuleSet.DATE_WINDOW_DAYS),
            txnDate.plusDays(RuleSet.DATE_WINDOW_DAYS),
        )

    override fun heldDocumentIds(): Set<UUID> =
        jdbc
            .query(
                """
                SELECT document_id
                FROM matches
                WHERE tenant_id = ?
                  AND document_id IS NOT NULL
                  AND status <> 'REJECTED'
                """.trimIndent(),
                { rs, _ -> rs.getObject("document_id", UUID::class.java) },
                tenants.id(),
            ).toSet()

    override fun lockMatch(transactionId: UUID): LockedMatch? =
        jdbc
            .query(
                """
                SELECT id, status, version, document_id, score, exception_type, rules_fired
                FROM matches
                WHERE transaction_id = ? AND tenant_id = ?
                FOR UPDATE
                """.trimIndent(),
                { rs, _ -> rs.toLockedMatch() },
                transactionId,
                tenants.id(),
            ).firstOrNull()

    override fun save(save: SaveMatch): SavedMatch {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val version = if (save.reopening) reopen(save, now) else insert(save, now)
        save.events.forEach { event ->
            entityManager.persist(
                ReconciliationEventEntity(
                    matchId = save.matchId,
                    eventType = event.eventType,
                    actor = event.actor,
                    reason = event.reason,
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
            "UPDATE card_transactions SET status = ? WHERE id = ? AND tenant_id = ?",
            save.finalStatus.name,
            save.transactionId,
            tenants.id(),
        )
        return SavedMatch(save.matchId, version = version, finalStatus = save.finalStatus)
    }

    private fun insert(
        save: SaveMatch,
        now: OffsetDateTime,
    ): Long {
        val candidate = save.decision.candidate
        entityManager.persist(
            MatchEntity(
                id = save.matchId,
                tenantId = tenants.id(),
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
        return 0
    }

    /**
     * The row is already locked. Hibernate's version column increments on this
     * update, so a concurrent writer of the same row cannot both commit.
     */
    private fun reopen(
        save: SaveMatch,
        now: OffsetDateTime,
    ): Long {
        val match =
            entityManager.find(MatchEntity::class.java, save.matchId)
                ?: error("Match ${save.matchId} disappeared after it was locked.")
        val candidate = save.decision.candidate
        match.documentId = candidate?.documentId
        match.status = save.finalStatus.name
        match.score = candidate?.score
        match.rulesFired.clear()
        match.rulesFired.addAll(save.decision.rulesFired.map { it.name })
        match.exceptionType = save.decision.exceptionType?.name
        match.decidedBy = save.decidedBy
        match.decidedAt = if (save.decidedBy == null) null else now
        entityManager.flush()
        return match.version
    }
}

private fun ResultSet.toLockedMatch(): LockedMatch =
    LockedMatch(
        matchId = getObject("id", UUID::class.java),
        status = MatchStatus.valueOf(getString("status")),
        version = getLong("version"),
        documentId = getObject("document_id", UUID::class.java),
        score = getBigDecimal("score"),
        exceptionType = getString("exception_type"),
        rulesFired = textList("rules_fired"),
    )

private fun ResultSet.textList(column: String): List<String> {
    val sqlArray = getArray(column) ?: return emptyList()
    val raw = sqlArray.array
    return when (raw) {
        is Array<*> -> raw.map { it.toString() }
        else -> emptyList()
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
