package com.hhok.spendrecon.adapters.`in`

import com.hhok.spendrecon.application.ApprovalService
import com.hhok.spendrecon.application.DecisionResponse
import com.hhok.spendrecon.application.StoredHttpResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.util.Locale
import java.util.UUID

@RestController
@SecurityRequirement(name = "apiKey")
class DecisionController(
    private val parser: DecisionJsonParser,
    private val approval: ApprovalService,
) {
    @PostMapping("/matches/{id}/approve")
    @Operation(
        summary = "Approve a match",
        description = "MATCHED and SUGGESTED approve with an optional reason. EXCEPTION requires a non-empty reason. The version must be the match's current version.",
        parameters = [
            Parameter(
                name = ApiHeaders.IDEMPOTENCY_KEY,
                `in` = ParameterIn.HEADER,
                required = true,
                description = "Client token. The same token and the same body replay the stored response.",
            ),
        ],
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "Approved, or a replay of that response.",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = DecisionResponse::class),
                    ),
                ],
            ),
            ApiResponse(responseCode = "400", description = "The body or the match id is invalid."),
            ApiResponse(responseCode = "401", description = "Missing or unknown X-API-Key."),
            ApiResponse(responseCode = "404", description = "No match with this id."),
            ApiResponse(responseCode = "409", description = "The version is stale, the match cannot be approved, or this key is in flight."),
            ApiResponse(responseCode = "422", description = "An exception was approved without a reason, or this key was used with a different body."),
        ],
    )
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
        required = true,
        content = [
            Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = Schema(implementation = ApproveBodyDoc::class),
            ),
        ],
    )
    fun approve(
        request: HttpServletRequest,
        @PathVariable id: String,
        @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) idempotencyKey: String?,
    ): ResponseEntity<String> {
        val matchId = parseId(id)
        val key = requireIdempotencyKey(idempotencyKey)
        val body = readBody(request)
        val command = parser.parseApprove(body)
        return jsonResponse(approval.approve(key, body, matchId, command))
    }

    @PostMapping("/matches/{id}/reject")
    @Operation(
        summary = "Reject a match",
        description = "Sets the match to REJECTED, returns the charge to UNMATCHED, and frees the receipt. The version must be the match's current version.",
        parameters = [
            Parameter(
                name = ApiHeaders.IDEMPOTENCY_KEY,
                `in` = ParameterIn.HEADER,
                required = true,
                description = "Client token. The same token and the same body replay the stored response.",
            ),
        ],
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "Rejected, or a replay of that response.",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = DecisionResponse::class),
                    ),
                ],
            ),
            ApiResponse(responseCode = "400", description = "The body or the match id is invalid. reason is required."),
            ApiResponse(responseCode = "401", description = "Missing or unknown X-API-Key."),
            ApiResponse(responseCode = "404", description = "No match with this id."),
            ApiResponse(responseCode = "409", description = "The version is stale, the match cannot be rejected, or this key is in flight."),
            ApiResponse(responseCode = "422", description = "This Idempotency-Key was used with a different body."),
        ],
    )
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
        required = true,
        content = [
            Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = Schema(implementation = RejectBodyDoc::class),
            ),
        ],
    )
    fun reject(
        request: HttpServletRequest,
        @PathVariable id: String,
        @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) idempotencyKey: String?,
    ): ResponseEntity<String> {
        val matchId = parseId(id)
        val key = requireIdempotencyKey(idempotencyKey)
        val body = readBody(request)
        val command = parser.parseReject(body)
        return jsonResponse(approval.reject(key, body, matchId, command))
    }

    private fun readBody(request: HttpServletRequest): ByteArray {
        val contentType = request.contentType?.lowercase(Locale.ROOT).orEmpty()
        if (!contentType.startsWith(MediaType.APPLICATION_JSON_VALUE)) {
            throw InvalidRequestException("Content-Type must be application/json.")
        }
        return request.inputStream.readAllBytes()
    }

    private fun parseId(id: String): UUID =
        try {
            UUID.fromString(id)
        } catch (_: IllegalArgumentException) {
            throw InvalidRequestException("id must be a UUID.")
        }

    private fun jsonResponse(outcome: StoredHttpResponse): ResponseEntity<String> =
        ResponseEntity
            .status(outcome.status)
            .contentType(MediaType.APPLICATION_JSON)
            .body(outcome.body)
}

/** OpenAPI shape for POST /matches/{id}/approve. The parser, not this class, reads the body. */
data class ApproveBodyDoc(
    @field:Schema(example = "0")
    val version: Long,
    @field:Schema(example = "sam")
    val actor: String,
    @field:Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, example = "Same trip, receipt arrived late")
    val reason: String? = null,
)

/** OpenAPI shape for POST /matches/{id}/reject. reason is required. */
data class RejectBodyDoc(
    @field:Schema(example = "0")
    val version: Long,
    @field:Schema(example = "sam")
    val actor: String,
    @field:Schema(example = "Receipt is for a different trip")
    val reason: String,
)
