package com.hhok.spendrecon.adapters.`in`

import com.hhok.spendrecon.CurrentTenant
import com.hhok.spendrecon.SpendReconProperties
import com.hhok.spendrecon.adapters.out.CardTransactionRepository
import com.hhok.spendrecon.adapters.out.DocumentRepository
import com.hhok.spendrecon.adapters.out.MatchRepository
import com.hhok.spendrecon.adapters.out.ReconciliationEventRepository
import com.hhok.spendrecon.application.TransactionNotFoundException
import com.hhok.spendrecon.domain.Currency
import com.hhok.spendrecon.domain.Money
import io.swagger.v3.oas.annotations.Operation
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

@RestController
class RecordController(
    private val transactions: CardTransactionRepository,
    private val documents: DocumentRepository,
    private val matches: MatchRepository,
    private val events: ReconciliationEventRepository,
    private val jdbc: JdbcTemplate,
    private val tenants: CurrentTenant,
    private val properties: SpendReconProperties,
) {
    @GetMapping("/transactions")
    @Operation(summary = "List charges for the current tenant")
    fun listTransactions(): TransactionPage = TransactionPage(transactions.findByTenantIdOrderByCreatedAtAscIdAsc(tenants.id()).map { it.toResponse() })

    @GetMapping("/transactions/{id}")
    @Operation(summary = "Read one charge in the current tenant")
    fun getTransaction(
        @PathVariable id: String,
    ): TransactionResponse {
        val txnId = parseUuid(id)
        return transactions
            .findByIdAndTenantId(txnId, tenants.id())
            .orElseThrow { TransactionNotFoundException() }
            .toResponse()
    }

    @GetMapping("/transactions/{id}/history")
    @Operation(summary = "Audit events for one charge")
    fun transactionHistory(
        @PathVariable id: String,
    ): List<MatchHistoryResponse> {
        val txnId = parseUuid(id)
        if (transactions.findByIdAndTenantId(txnId, tenants.id()).isEmpty) {
            throw TransactionNotFoundException()
        }
        val match = matches.findByTransactionIdAndTenantId(txnId, tenants.id()).orElse(null) ?: return emptyList()
        return events.findByMatchIdOrderByIdAsc(match.id).map { it.toHistoryResponse() }
    }

    @GetMapping("/documents")
    @Operation(summary = "List documents for the current tenant")
    fun listDocuments(): DocumentPage = DocumentPage(documents.findByTenantIdOrderByCreatedAtAscIdAsc(tenants.id()).map { it.toResponse() })

    @GetMapping("/demo/export")
    @Operation(summary = "CSV of APPROVED and MATCHED rows for the current tenant")
    fun exportMatches(): ResponseEntity<String> {
        if (!properties.demo.enabled) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND)
        }
        val rows =
            jdbc.query(
                """
                SELECT m.id AS match_id,
                       m.transaction_id,
                       m.document_id,
                       m.status,
                       m.score,
                       t.merchant_raw,
                       t.amount_minor,
                       t.currency,
                       t.txn_date,
                       m.decided_by,
                       m.decided_at
                FROM matches m
                JOIN card_transactions t
                  ON t.id = m.transaction_id
                 AND t.tenant_id = m.tenant_id
                WHERE m.tenant_id = ?
                  AND m.status IN ('APPROVED', 'MATCHED')
                ORDER BY m.created_at, m.id
                """.trimIndent(),
                { rs, _ ->
                    val currency = rs.getString("currency").trim()
                    listOf(
                        rs.getObject("match_id", UUID::class.java).toString(),
                        rs.getObject("transaction_id", UUID::class.java).toString(),
                        rs.getObject("document_id", UUID::class.java)?.toString(),
                        rs.getString("status"),
                        rs.getBigDecimal("score")?.toPlainString(),
                        rs.getString("merchant_raw"),
                        decimal(rs.getLong("amount_minor"), currency),
                        currency,
                        rs.getObject("txn_date", LocalDate::class.java).toString(),
                        rs.getString("decided_by"),
                        rs.getObject("decided_at", OffsetDateTime::class.java)?.toString(),
                    )
                },
                tenants.id(),
            )
        val body =
            buildString {
                append(EXPORT_HEADER)
                append('\n')
                rows.forEach { columns ->
                    append(columns.joinToString(",") { csv(it) })
                    append('\n')
                }
            }
        return ResponseEntity
            .ok()
            .contentType(MediaType.parseMediaType("text/csv"))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"matches.csv\"")
            .body(body)
    }
}

data class TransactionPage(
    val content: List<TransactionResponse>,
)

data class TransactionResponse(
    val id: UUID,
    val cardOwnerId: String,
    val merchant: String,
    val amount: String,
    val currency: String,
    val tax: String?,
    val txnDate: LocalDate,
    val status: String,
    val createdAt: OffsetDateTime,
)

data class DocumentPage(
    val content: List<DocumentResponse>,
)

data class DocumentResponse(
    val id: UUID,
    val kind: String,
    val cardOwnerId: String,
    val merchant: String,
    val amount: String,
    val currency: String,
    val tax: String?,
    val docDate: LocalDate,
    val createdAt: OffsetDateTime,
)

private const val EXPORT_HEADER =
    "matchId,transactionId,documentId,status,score,merchant,amount,currency,txnDate,decidedBy,decidedAt"

private fun csv(value: String?): String {
    if (value == null) {
        return ""
    }
    val escaped = value.replace("\"", "\"\"")
    return if (escaped.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"$escaped\"" else escaped
}

private fun decimal(
    minor: Long,
    currency: String,
): String = Money(minor, Currency(currency)).toDecimalString()

private fun com.hhok.spendrecon.adapters.out.CardTransactionEntity.toResponse(): TransactionResponse {
    val code = currency.trim()
    return TransactionResponse(
        id = id,
        cardOwnerId = cardOwnerId,
        merchant = merchantRaw,
        amount = decimal(amountMinor, code),
        currency = code,
        tax = taxMinor?.let { decimal(it, code) },
        txnDate = txnDate,
        status = status,
        createdAt = createdAt,
    )
}

private fun com.hhok.spendrecon.adapters.out.DocumentEntity.toResponse(): DocumentResponse {
    val code = currency.trim()
    return DocumentResponse(
        id = id,
        kind = kind,
        cardOwnerId = cardOwnerId,
        merchant = merchantRaw,
        amount = decimal(amountMinor, code),
        currency = code,
        tax = taxMinor?.let { decimal(it, code) },
        docDate = docDate,
        createdAt = createdAt,
    )
}

private fun parseUuid(id: String): UUID =
    try {
        UUID.fromString(id)
    } catch (_: IllegalArgumentException) {
        throw InvalidRequestException("id must be a UUID.")
    }
