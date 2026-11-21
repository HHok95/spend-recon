package com.hhok.spendrecon.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.hhok.spendrecon.domain.DecisionAction
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class ApprovalService(
    private val idempotency: IdempotencyService,
    private val store: DecisionStore,
    private val json: ObjectMapper,
) {
    fun approve(
        idempotencyKey: String,
        rawBody: ByteArray,
        matchId: UUID,
        command: DecisionCommand,
    ): StoredHttpResponse = decide(idempotencyKey, rawBody, matchId, command, DecisionAction.APPROVE)

    fun reject(
        idempotencyKey: String,
        rawBody: ByteArray,
        matchId: UUID,
        command: DecisionCommand,
    ): StoredHttpResponse = decide(idempotencyKey, rawBody, matchId, command, DecisionAction.REJECT)

    private fun decide(
        idempotencyKey: String,
        rawBody: ByteArray,
        matchId: UUID,
        command: DecisionCommand,
        action: DecisionAction,
    ): StoredHttpResponse {
        val endpoint =
            when (action) {
                DecisionAction.APPROVE -> IdempotencyEndpoints.approve(matchId)
                DecisionAction.REJECT -> IdempotencyEndpoints.reject(matchId)
            }
        return idempotency.execute(
            key = idempotencyKey,
            endpoint = endpoint,
            requestHash = requestSha256(rawBody),
        ) {
            val applied =
                store.apply(
                    ApplyDecision(
                        matchId = matchId,
                        expectedVersion = command.version,
                        action = action,
                        actor = command.actor,
                        reason = command.reason,
                    ),
                )
            StoredHttpResponse(
                status = 200,
                body =
                    json.writeValueAsString(
                        DecisionResponse(
                            matchId = applied.matchId,
                            status = applied.status.name,
                            version = applied.version,
                        ),
                    ),
                replay = false,
            )
        }
    }
}
