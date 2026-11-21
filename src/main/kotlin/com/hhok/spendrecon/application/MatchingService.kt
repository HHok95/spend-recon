package com.hhok.spendrecon.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.hhok.spendrecon.ReconProperties
import com.hhok.spendrecon.domain.ChargeView
import com.hhok.spendrecon.domain.MatchDecision
import com.hhok.spendrecon.domain.MatchStatus
import com.hhok.spendrecon.domain.ReceiptView
import com.hhok.spendrecon.domain.RuleSet
import com.hhok.spendrecon.domain.matchReceipts
import com.hhok.spendrecon.domain.shouldAutoApprove
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.UUID

data class ReconcileResponse(
    val matchId: UUID,
    val status: String,
    val score: java.math.BigDecimal?,
    val rulesFired: List<String>,
)

@Service
class MatchingService(
    private val idempotency: IdempotencyService,
    private val store: MatchingStore,
    private val properties: ReconProperties,
    private val json: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) {
    private val latency =
        Timer
            .builder("recon_match_latency")
            .description("Time to decide a match and write it")
            .register(meterRegistry)

    private val autoApprovals =
        Counter
            .builder("recon_auto_approvals")
            .description("Matches approved because the score met the threshold")
            .register(meterRegistry)

    fun reconcile(
        idempotencyKey: String,
        rawBody: ByteArray,
        transactionId: UUID,
    ): StoredHttpResponse =
        idempotency.execute(
            key = idempotencyKey,
            endpoint = IdempotencyEndpoints.reconcile(transactionId),
            requestHash = requestSha256(rawBody),
        ) {
            val started = System.nanoTime()
            val saved = decideAndSave(transactionId)
            latency.record(Duration.ofNanos(System.nanoTime() - started))
            StoredHttpResponse(
                status = 200,
                body =
                    json.writeValueAsString(
                        ReconcileResponse(
                            matchId = saved.matchId,
                            status = saved.finalStatus.name,
                            score = saved.score,
                            rulesFired = saved.rulesFired,
                        ),
                    ),
                replay = false,
            )
        }

    private fun decideAndSave(transactionId: UUID): Persisted {
        val charge = store.lockCharge(transactionId) ?: throw TransactionNotFoundException()
        if (store.hasMatch(transactionId)) {
            throw TransactionAlreadyMatchedException()
        }
        val receipts = store.lockReceipts()
        return persist(transactionId, charge, receipts, store.heldDocumentIds())
    }

    private fun persist(
        transactionId: UUID,
        charge: ChargeView,
        receipts: List<ReceiptView>,
        held: Set<UUID>,
    ): Persisted {
        val decision = matchReceipts(charge, receipts, held)
        val approved =
            shouldAutoApprove(
                decision.status,
                decision.candidate?.score,
                properties.autoApprove.threshold,
            )
        val finalStatus = if (approved) MatchStatus.APPROVED else decision.status
        val matchId = UUID.randomUUID()
        val events = auditEvents(decision, approved)
        val saved =
            store.save(
                SaveMatch(
                    matchId = matchId,
                    transactionId = transactionId,
                    finalStatus = finalStatus,
                    decision = decision,
                    decidedBy = if (approved) ACTOR_AUTO_APPROVE else null,
                    events = events,
                    outboxPayload = if (approved) outboxPayload(matchId, transactionId, decision) else null,
                ),
            )
        count(decision, approved)
        return Persisted(
            matchId = saved.matchId,
            version = saved.version,
            finalStatus = finalStatus,
            score = decision.candidate?.score,
            rulesFired = decision.rulesFired.map { it.name },
        )
    }

    private fun count(
        decision: MatchDecision,
        approved: Boolean,
    ) {
        Counter
            .builder("recon_matches")
            .description("Matches decided, before auto-approve")
            .tag("result", decision.status.name)
            .register(meterRegistry)
            .increment()
        if (decision.exceptionType != null) {
            Counter
                .builder("recon_exceptions")
                .description("Exception matches by type")
                .tag("type", decision.exceptionType.name)
                .register(meterRegistry)
                .increment()
        }
        if (approved) {
            autoApprovals.increment()
        }
    }

    private fun auditEvents(
        decision: MatchDecision,
        approved: Boolean,
    ): List<NewAuditEvent> {
        val fired = decision.rulesFired.map { it.name }
        val decided = snapshot(decision.status, decision, includeThreshold = false)
        val created =
            NewAuditEvent(
                eventType = decision.status.name,
                actor = ACTOR_MATCHING,
                rulesFired = fired,
                beforeJson = null,
                afterJson = json.writeValueAsString(decided),
            )
        if (!approved) {
            return listOf(created)
        }
        val approvedState = snapshot(MatchStatus.APPROVED, decision, includeThreshold = true)
        return listOf(
            created,
            NewAuditEvent(
                eventType = MatchStatus.APPROVED.name,
                actor = ACTOR_AUTO_APPROVE,
                rulesFired = fired,
                beforeJson = json.writeValueAsString(decided),
                afterJson = json.writeValueAsString(approvedState),
            ),
        )
    }

    private fun snapshot(
        status: MatchStatus,
        decision: MatchDecision,
        includeThreshold: Boolean,
    ): Map<String, Any?> {
        val body =
            linkedMapOf<String, Any?>(
                "status" to status.name,
                "score" to decision.candidate?.score,
                "documentId" to decision.candidate?.documentId,
                "exceptionType" to decision.exceptionType?.name,
                "rulesFired" to decision.rulesFired.map { it.name },
                "ruleSetVersion" to RuleSet.VERSION,
            )
        if (decision.tiedDocumentIds.isNotEmpty()) {
            body["tiedDocumentIds"] = decision.tiedDocumentIds
        }
        if (includeThreshold) {
            body["threshold"] = properties.autoApprove.threshold
        }
        return body
    }

    private fun outboxPayload(
        matchId: UUID,
        transactionId: UUID,
        decision: MatchDecision,
    ): String =
        json.writeValueAsString(
            linkedMapOf(
                "matchId" to matchId,
                "transactionId" to transactionId,
                "documentId" to decision.candidate?.documentId,
                "score" to decision.candidate?.score,
                "ruleSetVersion" to RuleSet.VERSION,
            ),
        )

    private data class Persisted(
        val matchId: UUID,
        val version: Long,
        val finalStatus: MatchStatus,
        val score: java.math.BigDecimal?,
        val rulesFired: List<String>,
    )
}
