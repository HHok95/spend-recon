package com.hhok.spendrecon.application

import com.hhok.spendrecon.domain.ChargeView
import com.hhok.spendrecon.domain.MatchDecision
import com.hhok.spendrecon.domain.MatchStatus
import com.hhok.spendrecon.domain.ReceiptView
import java.util.UUID

const val ACTOR_MATCHING = "system:matching"
const val ACTOR_AUTO_APPROVE = "system:auto-approve"
const val OUTBOX_MATCH_CONFIRMED = "MatchConfirmed"

class TransactionNotFoundException : RuntimeException("No transaction with this id.")

class MatchNotFoundException : RuntimeException("No match with this id.")

/** The charge already has a match row. Reuse of that row is a later pull request. */
class TransactionAlreadyMatchedException : RuntimeException()

/** The receipt unique index rejected the insert. The caller decides again. */
class ReceiptContendedException : RuntimeException()

data class NewAuditEvent(
    val eventType: String,
    val actor: String,
    val rulesFired: List<String>,
    val beforeJson: String?,
    val afterJson: String?,
)

data class SaveMatch(
    val matchId: UUID,
    val transactionId: UUID,
    val finalStatus: MatchStatus,
    val decision: MatchDecision,
    val decidedBy: String?,
    val events: List<NewAuditEvent>,
    val outboxPayload: String?,
)

data class SavedMatch(
    val matchId: UUID,
    val version: Long,
    val finalStatus: MatchStatus,
)

/**
 * Loads the charge and the receipts under row locks, then writes the match,
 * its audit rows, and the outbox row when auto-approve runs.
 * Called inside the idempotency transaction.
 */
interface MatchingStore {
    fun lockCharge(transactionId: UUID): ChargeView?

    fun lockReceipts(): List<ReceiptView>

    fun heldDocumentIds(): Set<UUID>

    fun hasMatch(transactionId: UUID): Boolean

    fun save(save: SaveMatch): SavedMatch
}
