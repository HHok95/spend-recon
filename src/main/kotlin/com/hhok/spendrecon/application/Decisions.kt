package com.hhok.spendrecon.application

import com.hhok.spendrecon.domain.DecisionAction
import com.hhok.spendrecon.domain.MatchStatus
import jakarta.persistence.OptimisticLockException
import org.hibernate.StaleStateException
import org.springframework.dao.OptimisticLockingFailureException
import java.util.UUID

data class DecisionCommand(
    val version: Long,
    val actor: String,
    val reason: String?,
)

data class DecisionResponse(
    val matchId: UUID,
    val status: String,
    val version: Long,
)

data class ApplyDecision(
    val matchId: UUID,
    val expectedVersion: Long,
    val action: DecisionAction,
    val actor: String,
    val reason: String?,
)

data class AppliedDecision(
    val matchId: UUID,
    val status: MatchStatus,
    val version: Long,
)

/** The client sent a version that is not the row's current version. The HTTP layer answers 409. */
class StaleVersionException : RuntimeException()

/** The version is current and the state machine does not allow the move. The HTTP layer answers 409. */
class IllegalTransitionException(
    message: String,
) : RuntimeException(message)

/** Approving an EXCEPTION without a reason. The HTTP layer answers 422. */
class OverrideReasonRequiredException(
    message: String,
) : RuntimeException(message)

/**
 * Writes one transition: the match row, one audit event, the charge status,
 * and the outbox row when the action is approve.
 * Called inside the idempotency transaction.
 */
interface DecisionStore {
    fun apply(command: ApplyDecision): AppliedDecision
}

/** True when Hibernate or Spring reported that a @Version update lost a race. */
fun Throwable.causedByOptimisticLock(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is OptimisticLockException ||
            current is OptimisticLockingFailureException ||
            current is StaleStateException
        ) {
            return true
        }
        current = current.cause
    }
    return false
}
