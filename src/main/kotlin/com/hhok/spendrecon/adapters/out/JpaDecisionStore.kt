package com.hhok.spendrecon.adapters.out

import com.fasterxml.jackson.databind.ObjectMapper
import com.hhok.spendrecon.application.ApplyDecision
import com.hhok.spendrecon.application.AppliedDecision
import com.hhok.spendrecon.application.DecisionStore
import com.hhok.spendrecon.application.IllegalTransitionException
import com.hhok.spendrecon.application.MatchNotFoundException
import com.hhok.spendrecon.application.OUTBOX_MATCH_CONFIRMED
import com.hhok.spendrecon.application.OverrideReasonRequiredException
import com.hhok.spendrecon.application.StaleVersionException
import com.hhok.spendrecon.application.TransactionNotFoundException
import com.hhok.spendrecon.application.UNMATCHED
import com.hhok.spendrecon.application.causedByOptimisticLock
import com.hhok.spendrecon.domain.DecisionAction
import com.hhok.spendrecon.domain.MatchStatus
import com.hhok.spendrecon.domain.RuleSet
import com.hhok.spendrecon.domain.Transition
import com.hhok.spendrecon.domain.TransitionRefusal
import com.hhok.spendrecon.domain.refusalMessage
import com.hhok.spendrecon.domain.transition
import jakarta.persistence.EntityManager
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JpaDecisionStore(
    private val entityManager: EntityManager,
    private val jdbc: JdbcTemplate,
    private val json: ObjectMapper,
) : DecisionStore {
    @Transactional
    override fun apply(command: ApplyDecision): AppliedDecision {
        // Lock order for one charge: card_transactions, then matches, then receipts.
        // The id read does not lock the match. Reconcile uses this same order.
        val transactionId = transactionIdOf(command.matchId)
        lockCharge(transactionId)
        val match =
            entityManager.find(MatchEntity::class.java, command.matchId)
                ?: throw MatchNotFoundException()
        if (match.version != command.expectedVersion) {
            throw StaleVersionException()
        }
        val current =
            try {
                MatchStatus.valueOf(match.status)
            } catch (_: IllegalArgumentException) {
                throw IllegalTransitionException("This match is ${match.status} and cannot change.")
            }
        val next =
            when (val outcome = transition(current, command.action, command.reason)) {
                is Transition.Allowed -> outcome.next
                is Transition.Refused ->
                    when (outcome.refusal) {
                        TransitionRefusal.OVERRIDE_REASON_REQUIRED ->
                            throw OverrideReasonRequiredException(
                                refusalMessage(current, command.action, outcome.refusal),
                            )
                        TransitionRefusal.NOT_ALLOWED ->
                            throw IllegalTransitionException(
                                refusalMessage(current, command.action, outcome.refusal),
                            )
                    }
            }
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val before = snapshot(match, current.name, match.version)
        match.status = next.name
        match.decidedBy = command.actor
        match.decidedAt = now
        flushOrStale()
        entityManager.persist(
            ReconciliationEventEntity(
                matchId = match.id,
                eventType = next.name,
                actor = command.actor,
                reason = command.reason,
                rulesFired = match.rulesFired.toMutableList(),
                beforeState = json.readTree(before),
                afterState = json.readTree(snapshot(match, next.name, match.version)),
                createdAt = now,
            ),
        )
        if (command.action == DecisionAction.APPROVE) {
            jdbc.update(
                """
                INSERT INTO outbox (aggregate_id, event_type, payload)
                VALUES (?, ?, ?::jsonb)
                """.trimIndent(),
                match.id,
                OUTBOX_MATCH_CONFIRMED,
                outboxPayload(match, command.actor),
            )
        }
        val chargeStatus = if (next == MatchStatus.REJECTED) UNMATCHED else next.name
        jdbc.update(
            "UPDATE card_transactions SET status = ? WHERE id = ?",
            chargeStatus,
            match.transactionId,
        )
        flushOrStale()
        return AppliedDecision(matchId = match.id, status = next, version = match.version)
    }

    private fun transactionIdOf(matchId: UUID): UUID =
        jdbc
            .query(
                "SELECT transaction_id FROM matches WHERE id = ?",
                { rs, _ -> rs.getObject("transaction_id", UUID::class.java) },
                matchId,
            ).firstOrNull() ?: throw MatchNotFoundException()

    private fun lockCharge(transactionId: UUID) {
        val locked =
            jdbc.query(
                "SELECT 1 FROM card_transactions WHERE id = ? FOR UPDATE",
                { _, _ -> 1 },
                transactionId,
            )
        if (locked.isEmpty()) {
            throw TransactionNotFoundException()
        }
    }

    private fun flushOrStale() {
        try {
            entityManager.flush()
        } catch (exception: RuntimeException) {
            if (exception.causedByOptimisticLock()) {
                throw StaleVersionException()
            }
            throw exception
        }
    }

    private fun snapshot(
        match: MatchEntity,
        status: String,
        version: Long,
    ): String =
        json.writeValueAsString(
            linkedMapOf(
                "status" to status,
                "version" to version,
                "score" to match.score,
                "documentId" to match.documentId,
                "exceptionType" to match.exceptionType,
                "rulesFired" to match.rulesFired.toList(),
                "ruleSetVersion" to RuleSet.VERSION,
            ),
        )

    /**
     * Same fields as an automatic MatchConfirmed, plus the person who approved.
     * The consumer tells a manual approval from system:auto-approve by that field.
     */
    private fun outboxPayload(
        match: MatchEntity,
        actor: String,
    ): String =
        json.writeValueAsString(
            linkedMapOf(
                "matchId" to match.id,
                "transactionId" to match.transactionId,
                "documentId" to match.documentId,
                "score" to match.score,
                "ruleSetVersion" to RuleSet.VERSION,
                "actor" to actor,
            ),
        )
}
