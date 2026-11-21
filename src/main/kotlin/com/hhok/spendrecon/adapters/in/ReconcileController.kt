package com.hhok.spendrecon.adapters.`in`

import com.fasterxml.jackson.databind.ObjectMapper
import com.hhok.spendrecon.application.MatchingService
import com.hhok.spendrecon.application.StoredHttpResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.enums.ParameterIn
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
class ReconcileController(
    private val matching: MatchingService,
    private val json: ObjectMapper,
) {
    @PostMapping("/reconcile/{txnId}")
    @Operation(
        summary = "Match a card transaction to a receipt",
        parameters = [
            Parameter(
                name = ApiHeaders.IDEMPOTENCY_KEY,
                `in` = ParameterIn.HEADER,
                required = true,
                description = "Client token. The same token and the same body replay the stored response.",
            ),
        ],
        responses = [
            ApiResponse(responseCode = "200", description = "The match, or a replay of that response."),
            ApiResponse(responseCode = "400", description = "The transaction id or the body is invalid."),
            ApiResponse(responseCode = "401", description = "Missing or unknown X-API-Key."),
            ApiResponse(responseCode = "404", description = "No transaction with this id."),
            ApiResponse(responseCode = "409", description = "This key is in flight, or the transaction already has a match."),
            ApiResponse(responseCode = "422", description = "This Idempotency-Key was used with a different body."),
        ],
    )
    fun reconcile(
        request: HttpServletRequest,
        @PathVariable txnId: String,
        @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) idempotencyKey: String?,
    ): ResponseEntity<String> {
        val id =
            try {
                UUID.fromString(txnId)
            } catch (_: IllegalArgumentException) {
                throw InvalidRequestException("txnId must be a UUID.")
            }
        val key = requireIdempotencyKey(idempotencyKey)
        val body = readBody(request)
        return jsonResponse(matching.reconcile(key, body, id))
    }

    /**
     * The hash is the raw bytes. An empty body is allowed. A non-empty body must be JSON,
     * and it is checked before the idempotency insert so a 400 does not consume the key.
     */
    private fun readBody(request: HttpServletRequest): ByteArray {
        val body = request.inputStream.readAllBytes()
        if (body.isEmpty()) {
            return body
        }
        val contentType = request.contentType?.lowercase(Locale.ROOT).orEmpty()
        if (!contentType.startsWith(MediaType.APPLICATION_JSON_VALUE)) {
            throw InvalidRequestException("Content-Type must be application/json.")
        }
        try {
            json.readTree(body)
        } catch (_: Exception) {
            throw InvalidRequestException("Request body must be JSON.")
        }
        return body
    }

    private fun jsonResponse(outcome: StoredHttpResponse): ResponseEntity<String> =
        ResponseEntity
            .status(outcome.status)
            .contentType(MediaType.APPLICATION_JSON)
            .body(outcome.body)
}

internal fun requireIdempotencyKey(value: String?): String {
    if (value.isNullOrBlank()) {
        throw InvalidRequestException("The Idempotency-Key header is required.")
    }
    if (value.length > ApiHeaders.MAX_IDEMPOTENCY_KEY_LENGTH) {
        throw InvalidRequestException("The Idempotency-Key header must be at most 255 characters.")
    }
    return value
}
