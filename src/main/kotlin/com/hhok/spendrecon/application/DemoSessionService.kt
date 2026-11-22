package com.hhok.spendrecon.application

import com.hhok.spendrecon.SpendReconProperties
import com.hhok.spendrecon.domain.normalizeMerchant
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID

data class OpenedDemoSession(
    val tenantId: UUID,
    val token: String,
    val expiresAt: OffsetDateTime,
)

data class DemoSessionHit(
    val tenantId: UUID,
    val active: Boolean,
)

@Service
class DemoSessionService(
    private val jdbc: JdbcTemplate,
    private val seed: DemoSeed,
    private val properties: SpendReconProperties,
) {
    private val random = SecureRandom()

    @Transactional
    fun open(): OpenedDemoSession {
        val tenantId = UUID.randomUUID()
        val expiresAt =
            OffsetDateTime
                .now(ZoneOffset.UTC)
                .plus(properties.demo.sessionTtl)
                .truncatedTo(ChronoUnit.SECONDS)
        jdbc.update(
            "INSERT INTO tenants (id, expires_at) VALUES (?, ?)",
            tenantId,
            expiresAt,
        )
        val token = newToken()
        jdbc.update(
            "INSERT INTO demo_sessions (token_hash, tenant_id, expires_at) VALUES (?, ?, ?)",
            requestSha256(token.toByteArray(Charsets.UTF_8)),
            tenantId,
            expiresAt,
        )
        seed.load(tenantId)
        return OpenedDemoSession(tenantId, token, expiresAt)
    }

    fun lookup(token: String): DemoSessionHit? =
        jdbc
            .query(
                """
                SELECT tenant_id, (expires_at > now()) AS active
                FROM demo_sessions
                WHERE token_hash = ?
                """.trimIndent(),
                { rs, _ ->
                    DemoSessionHit(
                        tenantId = rs.getObject("tenant_id", UUID::class.java),
                        active = rs.getBoolean("active"),
                    )
                },
                requestSha256(token.toByteArray(Charsets.UTF_8)),
            ).firstOrNull()

    private fun newToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

@Service
class DemoSeed(
    private val jdbc: JdbcTemplate,
) {
    /**
     * Three charges so a new session has something to reconcile, something to approve,
     * and something already on the timeline. Tax is equal on the coffee pair so the
     * score is 1.000 and reconcile auto-approves. The books receipt differs in amount.
     */
    fun load(tenantId: UUID) {
        val coffeeTxn = UUID.randomUUID()
        val coffeeDoc = UUID.randomUUID()
        val booksTxn = UUID.randomUUID()
        val booksDoc = UUID.randomUUID()
        val tramTxn = UUID.randomUUID()
        val tramDoc = UUID.randomUUID()
        val tramMatch = UUID.randomUUID()
        insertCharge(tenantId, coffeeTxn, "Northwind Coffee", 1840, 167, "UNMATCHED")
        insertReceipt(tenantId, coffeeDoc, "Northwind Coffee", 1840, 167)
        insertCharge(tenantId, booksTxn, "City Books", 4200, 382, "UNMATCHED")
        insertReceipt(tenantId, booksDoc, "City Books", 4000, 364)
        insertCharge(tenantId, tramTxn, "Tram Fare", 460, 42, "APPROVED")
        insertReceipt(tenantId, tramDoc, "Tram Fare", 460, 42)
        insertApproved(tenantId, tramMatch, tramTxn, tramDoc)
    }

    private fun insertCharge(
        tenantId: UUID,
        id: UUID,
        merchant: String,
        amountMinor: Long,
        taxMinor: Long,
        status: String,
    ) {
        jdbc.update(
            """
            INSERT INTO card_transactions (
                id, tenant_id, card_owner_id, merchant_raw, merchant_norm,
                amount_minor, currency, tax_minor, txn_date, status
            ) VALUES (?, ?, 'visitor', ?, ?, ?, 'AUD', ?, DATE '2026-11-18', ?)
            """.trimIndent(),
            id,
            tenantId,
            merchant,
            normalizeMerchant(merchant),
            amountMinor,
            taxMinor,
            status,
        )
    }

    private fun insertReceipt(
        tenantId: UUID,
        id: UUID,
        merchant: String,
        amountMinor: Long,
        taxMinor: Long,
    ) {
        jdbc.update(
            """
            INSERT INTO documents (
                id, tenant_id, kind, card_owner_id, merchant_raw, merchant_norm,
                amount_minor, currency, tax_minor, doc_date
            ) VALUES (?, ?, 'RECEIPT', 'visitor', ?, ?, ?, 'AUD', ?, DATE '2026-11-18')
            """.trimIndent(),
            id,
            tenantId,
            merchant,
            normalizeMerchant(merchant),
            amountMinor,
            taxMinor,
        )
    }

    private fun insertApproved(
        tenantId: UUID,
        matchId: UUID,
        transactionId: UUID,
        documentId: UUID,
    ) {
        jdbc.update(
            """
            INSERT INTO matches (
                id, tenant_id, transaction_id, document_id, status, score,
                rules_fired, version, decided_by, decided_at
            ) VALUES (
                ?, ?, ?, ?, 'APPROVED', 1.000,
                ARRAY['R1_CURRENCY','R2_OWNER','R3_AMOUNT','R4_DATE','R5_MERCHANT','R6_TAX'],
                0, 'system:auto-approve', now()
            )
            """.trimIndent(),
            matchId,
            tenantId,
            transactionId,
            documentId,
        )
        jdbc.update(EVENT_SQL, matchId, "MATCHED", "system:matching", "MATCHED")
        jdbc.update(EVENT_SQL, matchId, "APPROVED", "system:auto-approve", "APPROVED")
    }

    private companion object {
        val EVENT_SQL =
            """
            INSERT INTO reconciliation_events (match_id, event_type, actor, rules_fired, after)
            VALUES (
                ?, ?, ?,
                ARRAY['R1_CURRENCY','R2_OWNER','R3_AMOUNT','R4_DATE','R5_MERCHANT','R6_TAX'],
                jsonb_build_object('status', CAST(? AS text), 'score', '1.000')
            )
            """.trimIndent()
    }
}
