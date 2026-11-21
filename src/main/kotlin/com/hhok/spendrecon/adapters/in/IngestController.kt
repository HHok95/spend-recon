package com.hhok.spendrecon.adapters.`in`

import com.hhok.spendrecon.application.IngestService
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
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.util.Locale

@RestController
@SecurityRequirement(name = "apiKey")
class IngestController(
    private val parser: IngestJsonParser,
    private val ingest: IngestService,
) {
    @PostMapping("/transactions")
    @Operation(
        summary = "Record a card transaction",
        parameters = [
            Parameter(
                name = ApiHeaders.IDEMPOTENCY_KEY,
                `in` = ParameterIn.HEADER,
                required = true,
                description = "Client token. The same token and the same body replay the stored response.",
            ),
        ],
        responses = [
            ApiResponse(responseCode = "201", description = "Created, or a replay of the created response."),
            ApiResponse(responseCode = "400", description = "The body is invalid. Amounts are decimal strings."),
            ApiResponse(responseCode = "401", description = "Missing or unknown X-API-Key."),
            ApiResponse(responseCode = "409", description = "This Idempotency-Key is still in flight."),
            ApiResponse(responseCode = "422", description = "This Idempotency-Key was used with a different body."),
        ],
    )
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
        required = true,
        content = [
            Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = Schema(implementation = TransactionBodyDoc::class),
            ),
        ],
    )
    fun createTransaction(
        request: HttpServletRequest,
        @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) idempotencyKey: String?,
    ): ResponseEntity<String> {
        val key = requireIdempotencyKey(idempotencyKey)
        val body = readBody(request)
        val command = parser.parseTransaction(body)
        return json(ingest.createTransaction(key, body, command))
    }

    @PostMapping("/documents")
    @Operation(
        summary = "Record a receipt or a bill",
        parameters = [
            Parameter(
                name = ApiHeaders.IDEMPOTENCY_KEY,
                `in` = ParameterIn.HEADER,
                required = true,
                description = "Client token. Scoped to this route, separate from POST /transactions.",
            ),
        ],
        responses = [
            ApiResponse(responseCode = "201", description = "Created, or a replay of the created response."),
            ApiResponse(responseCode = "400", description = "The body is invalid. Amounts are decimal strings."),
            ApiResponse(responseCode = "401", description = "Missing or unknown X-API-Key."),
            ApiResponse(responseCode = "409", description = "This Idempotency-Key is still in flight."),
            ApiResponse(responseCode = "422", description = "This Idempotency-Key was used with a different body."),
        ],
    )
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
        required = true,
        content = [
            Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = Schema(implementation = DocumentBodyDoc::class),
            ),
        ],
    )
    fun createDocument(
        request: HttpServletRequest,
        @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) idempotencyKey: String?,
    ): ResponseEntity<String> {
        val key = requireIdempotencyKey(idempotencyKey)
        val body = readBody(request)
        val command = parser.parseDocument(body)
        return json(ingest.createDocument(key, body, command))
    }

    /**
     * The hash is the raw bytes, so the body is read here instead of bound to a DTO.
     * Validation runs before the idempotency insert: a 400 does not consume the key.
     */
    private fun readBody(request: HttpServletRequest): ByteArray {
        val contentType = request.contentType?.lowercase(Locale.ROOT).orEmpty()
        if (!contentType.startsWith(MediaType.APPLICATION_JSON_VALUE)) {
            throw InvalidRequestException("Content-Type must be application/json.")
        }
        return request.inputStream.readAllBytes()
    }

    private fun requireIdempotencyKey(value: String?): String {
        if (value.isNullOrBlank()) {
            throw InvalidRequestException("The Idempotency-Key header is required.")
        }
        if (value.length > ApiHeaders.MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw InvalidRequestException("The Idempotency-Key header must be at most 255 characters.")
        }
        return value
    }

    private fun json(outcome: StoredHttpResponse): ResponseEntity<String> =
        ResponseEntity
            .status(outcome.status)
            .contentType(MediaType.APPLICATION_JSON)
            .body(outcome.body)
}

/** OpenAPI shape for POST /transactions. The parser, not this class, reads the body. */
data class TransactionBodyDoc(
    val cardOwnerId: String,
    val merchant: String,
    @field:Schema(description = "Decimal string, for example 12.34. Not a JSON number.", example = "12.34")
    val amount: String,
    @field:Schema(example = "AUD")
    val currency: String,
    @field:Schema(example = "2026-11-21")
    val txnDate: String,
    @field:Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, example = "1.12")
    val tax: String? = null,
)

/** OpenAPI shape for POST /documents. */
data class DocumentBodyDoc(
    @field:Schema(allowableValues = ["RECEIPT", "BILL"])
    val kind: String,
    val cardOwnerId: String,
    val merchant: String,
    @field:Schema(description = "Decimal string, for example 12.34. Not a JSON number.", example = "12.34")
    val amount: String,
    @field:Schema(example = "AUD")
    val currency: String,
    @field:Schema(example = "2026-11-21")
    val docDate: String,
    @field:Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, example = "1.12")
    val tax: String? = null,
)
