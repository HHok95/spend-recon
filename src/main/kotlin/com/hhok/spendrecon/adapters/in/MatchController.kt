package com.hhok.spendrecon.adapters.`in`

import com.hhok.spendrecon.CurrentTenant
import com.hhok.spendrecon.adapters.out.MatchEntity
import com.hhok.spendrecon.adapters.out.MatchRepository
import com.hhok.spendrecon.adapters.out.ReconciliationEventEntity
import com.hhok.spendrecon.adapters.out.ReconciliationEventRepository
import com.hhok.spendrecon.application.MatchNotFoundException
import com.hhok.spendrecon.domain.MatchStatus
import io.swagger.v3.oas.annotations.Operation
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

@RestController
class MatchController(
    private val matches: MatchRepository,
    private val events: ReconciliationEventRepository,
    private val tenants: CurrentTenant,
) {
    @GetMapping("/matches")
    @Operation(summary = "List matches, optionally filtered by status")
    fun list(
        @RequestParam(required = false) status: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): MatchPageResponse {
        if (page < 0) {
            throw InvalidRequestException("page must be zero or greater.")
        }
        if (size !in 1..100) {
            throw InvalidRequestException("size must be from 1 to 100.")
        }
        val filter = parseStatus(status)
        val pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id"))
        val tenantId = tenants.id()
        val result =
            if (filter == null) {
                matches.findByTenantId(tenantId, pageable)
            } else {
                matches.findByTenantIdAndStatus(tenantId, filter.name, pageable)
            }
        return MatchPageResponse(
            content = result.content.map { it.toResponse() },
            page = page,
            size = size,
            totalElements = result.totalElements,
        )
    }

    @GetMapping("/matches/{id}")
    @Operation(summary = "Read one match, including its version")
    fun get(
        @PathVariable id: String,
    ): MatchResponse {
        val matchId = parseId(id)
        val match = matches.findByIdAndTenantId(matchId, tenants.id()).orElseThrow { MatchNotFoundException() }
        return match.toResponse()
    }

    @GetMapping("/matches/{id}/history")
    @Operation(summary = "Read the audit events for one match")
    fun history(
        @PathVariable id: String,
    ): List<MatchHistoryResponse> {
        val matchId = parseId(id)
        if (matches.findByIdAndTenantId(matchId, tenants.id()).isEmpty) {
            throw MatchNotFoundException()
        }
        return events.findByMatchIdOrderByIdAsc(matchId).map { it.toHistoryResponse() }
    }

    private fun parseStatus(status: String?): MatchStatus? {
        if (status.isNullOrBlank()) {
            return null
        }
        return try {
            MatchStatus.valueOf(status)
        } catch (_: IllegalArgumentException) {
            throw InvalidRequestException("status must be one of ${MatchStatus.entries.joinToString { it.name }}.")
        }
    }

    private fun parseId(id: String): UUID =
        try {
            UUID.fromString(id)
        } catch (_: IllegalArgumentException) {
            throw InvalidRequestException("id must be a UUID.")
        }
}

data class MatchPageResponse(
    val content: List<MatchResponse>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
)

data class MatchResponse(
    val matchId: UUID,
    val transactionId: UUID,
    val documentId: UUID?,
    val status: String,
    val score: BigDecimal?,
    val rulesFired: List<String>,
    val exceptionType: String?,
    val version: Long,
    val decidedBy: String?,
    val decidedAt: OffsetDateTime?,
    val createdAt: OffsetDateTime,
)

data class MatchHistoryResponse(
    val id: Long,
    val eventType: String,
    val actor: String,
    val reason: String?,
    val rulesFired: List<String>,
    val before: com.fasterxml.jackson.databind.JsonNode?,
    val after: com.fasterxml.jackson.databind.JsonNode?,
    val createdAt: OffsetDateTime,
)

private fun MatchEntity.toResponse(): MatchResponse =
    MatchResponse(
        matchId = id,
        transactionId = transactionId,
        documentId = documentId,
        status = status,
        score = score,
        rulesFired = rulesFired.toList(),
        exceptionType = exceptionType,
        version = version,
        decidedBy = decidedBy,
        decidedAt = decidedAt,
        createdAt = createdAt,
    )

internal fun ReconciliationEventEntity.toHistoryResponse(): MatchHistoryResponse =
    MatchHistoryResponse(
        id = id ?: error("Event id was not assigned."),
        eventType = eventType,
        actor = actor,
        reason = reason,
        rulesFired = rulesFired.toList(),
        before = beforeState,
        after = afterState,
        createdAt = createdAt,
    )
