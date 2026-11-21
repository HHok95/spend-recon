package com.hhok.spendrecon.domain

/** A person's decision on a match that matching already stored. */
enum class DecisionAction {
    APPROVE,
    REJECT,
}

enum class TransitionRefusal {
    OVERRIDE_REASON_REQUIRED,
    NOT_ALLOWED,
}

sealed class Transition {
    data class Allowed(
        val next: MatchStatus,
    ) : Transition()

    data class Refused(
        val refusal: TransitionRefusal,
    ) : Transition()
}

/**
 * Section 5 of the architecture. MATCHED, SUGGESTED, and EXCEPTION can move.
 * APPROVED and REJECTED cannot. An EXCEPTION can be approved only with a reason.
 * A blank reason is missing. Reject always carries a reason, checked before this runs.
 */
fun transition(
    current: MatchStatus,
    action: DecisionAction,
    reason: String?,
): Transition {
    if (action == DecisionAction.APPROVE && current == MatchStatus.EXCEPTION && reason.isNullOrBlank()) {
        return Transition.Refused(TransitionRefusal.OVERRIDE_REASON_REQUIRED)
    }
    val open =
        when (current) {
            MatchStatus.MATCHED, MatchStatus.SUGGESTED, MatchStatus.EXCEPTION -> true
            MatchStatus.APPROVED, MatchStatus.REJECTED -> false
        }
    if (!open) {
        return Transition.Refused(TransitionRefusal.NOT_ALLOWED)
    }
    val next = if (action == DecisionAction.APPROVE) MatchStatus.APPROVED else MatchStatus.REJECTED
    return Transition.Allowed(next)
}

fun refusalMessage(
    current: MatchStatus,
    action: DecisionAction,
    refusal: TransitionRefusal,
): String =
    when (refusal) {
        TransitionRefusal.OVERRIDE_REASON_REQUIRED ->
            "Approving an exception requires a non-empty reason."
        TransitionRefusal.NOT_ALLOWED -> {
            val verb = if (action == DecisionAction.APPROVE) "approved" else "rejected"
            "This match is ${current.name} and cannot be $verb."
        }
    }
