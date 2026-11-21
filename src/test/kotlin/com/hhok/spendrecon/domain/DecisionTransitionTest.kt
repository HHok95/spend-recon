package com.hhok.spendrecon.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DecisionTransitionTest {
    @Test
    fun `matched suggested and exception can be approved or rejected`() {
        listOf(MatchStatus.MATCHED, MatchStatus.SUGGESTED).forEach { status ->
            assertThat(transition(status, DecisionAction.APPROVE, reason = null)).isEqualTo(Transition.Allowed(MatchStatus.APPROVED))
            assertThat(transition(status, DecisionAction.REJECT, reason = "no")).isEqualTo(Transition.Allowed(MatchStatus.REJECTED))
        }
        assertThat(transition(MatchStatus.EXCEPTION, DecisionAction.APPROVE, reason = "override"))
            .isEqualTo(Transition.Allowed(MatchStatus.APPROVED))
        assertThat(transition(MatchStatus.EXCEPTION, DecisionAction.REJECT, reason = "no"))
            .isEqualTo(Transition.Allowed(MatchStatus.REJECTED))
    }

    @Test
    fun `an exception approve without a reason is refused`() {
        listOf(null, "", "  ").forEach { reason ->
            assertThat(transition(MatchStatus.EXCEPTION, DecisionAction.APPROVE, reason))
                .isEqualTo(Transition.Refused(TransitionRefusal.OVERRIDE_REASON_REQUIRED))
        }
    }

    @Test
    fun `approved and rejected are terminal`() {
        listOf(MatchStatus.APPROVED, MatchStatus.REJECTED).forEach { status ->
            assertThat(transition(status, DecisionAction.APPROVE, reason = "again"))
                .isEqualTo(Transition.Refused(TransitionRefusal.NOT_ALLOWED))
            assertThat(transition(status, DecisionAction.REJECT, reason = "again"))
                .isEqualTo(Transition.Refused(TransitionRefusal.NOT_ALLOWED))
        }
        assertThat(
            refusalMessage(MatchStatus.APPROVED, DecisionAction.APPROVE, TransitionRefusal.NOT_ALLOWED),
        ).isEqualTo("This match is APPROVED and cannot be approved.")
        assertThat(
            refusalMessage(MatchStatus.EXCEPTION, DecisionAction.APPROVE, TransitionRefusal.OVERRIDE_REASON_REQUIRED),
        ).isEqualTo("Approving an exception requires a non-empty reason.")
    }
}
