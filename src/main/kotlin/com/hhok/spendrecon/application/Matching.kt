package com.hhok.spendrecon.application

import com.hhok.spendrecon.domain.ChargeView
import com.hhok.spendrecon.domain.MatchDecision
import com.hhok.spendrecon.domain.MatchStatus
import com.hhok.spendrecon.domain.ReceiptView
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

const val ACTOR_MATCHING = "system:matching"
const val ACTOR_AUTO_APPROVE = "system:auto-approve"
const val OUTBOX_MATCH_CONFIRMED = "MatchConfirmed"

class TransactionNotFoundException : RuntimeException("No transaction with this id.")

class MatchNotFoundException : RuntimeException("No match with this id.")

/** The charge has a match that is not REJECTED. A rejected row is reused instead. */
class TransactionAlreadyMatchedException : RuntimeException()

data class NewAuditEvent(
    val eventType: String,
    val actor: String,
    val rulesFired: List<String>,
    val beforeJson: String?,
    val afterJson: String?,
    val reason: String? = null,
)

data class SaveMatch(
    val matchId: UUID,
    val transactionId: UUID,
    val finalStatus: MatchStatus,
    val decision: MatchDecision,
    val decidedBy: String?,
    val events: List<NewAuditEvent>,
    val outboxPayload: String?,
    /** True when [matchId] is an existing REJECTED row to update, not a new insert. */
    val reopening: Boolean = false,
)

/**
 * The match row locked for this charge. [status] REJECTED is the only status a
 * later reconcile may move. [version] is the row's version before that move.
 */
data class LockedMatch(
    val matchId: UUID,
    val status: MatchStatus,
    val version: Long,
    val documentId: UUID?,
    val score: BigDecimal?,
    val exceptionType: String?,
    val rulesFired: List<String>,
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
 * Receipt locks are the charge's owner and the three-day date window, in id order.
 */
interface MatchingStore {
    fun lockCharge(transactionId: UUID): ChargeView?

    fun lockReceipts(
        ownerId: String,
        txnDate: LocalDate,
    ): List<ReceiptView>

    fun heldDocumentIds(): Set<UUID>

    /** Locks the match for this charge, if one exists. Call after [lockCharge]. */
    fun lockMatch(transactionId: UUID): LockedMatch?

    fun save(save: SaveMatch): SavedMatch
}
