package com.hhok.spendrecon.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.hhok.spendrecon.domain.DocumentKind
import com.hhok.spendrecon.domain.Money
import com.hhok.spendrecon.domain.normalizeDocumentNumber
import com.hhok.spendrecon.domain.normalizeMerchant
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.util.UUID

/** Status written at ingest. Later pull requests move a transaction through matching. */
const val UNMATCHED = "UNMATCHED"

data class IngestTransactionCommand(
    val cardOwnerId: String,
    val merchantRaw: String,
    val amount: Money,
    val tax: Money?,
    val txnDate: LocalDate,
)

data class IngestDocumentCommand(
    val kind: DocumentKind,
    val cardOwnerId: String,
    val merchantRaw: String,
    val amount: Money,
    val tax: Money?,
    val docDate: LocalDate,
    /** Receipt or invoice number. Null when the document has none. */
    val documentNumber: String?,
)

data class TransactionCreatedResponse(
    val id: UUID,
    val status: String,
)

data class DocumentCreatedResponse(
    val id: UUID,
)

/** The merchant already has this receipt or invoice number. The HTTP layer answers 409. */
class DuplicateDocumentException : RuntimeException(
    "A document with this receipt or invoice number already exists for this merchant.",
)

interface IngestStore {
    fun insertTransaction(
        command: IngestTransactionCommand,
        merchantNorm: String,
    ): UUID

    fun insertDocument(
        command: IngestDocumentCommand,
        merchantNorm: String,
        documentNumberNorm: String?,
    ): UUID
}

@Service
class IngestService(
    private val idempotency: IdempotencyService,
    private val store: IngestStore,
    private val json: ObjectMapper,
) {
    fun createTransaction(
        idempotencyKey: String,
        rawBody: ByteArray,
        command: IngestTransactionCommand,
    ): StoredHttpResponse =
        idempotency.execute(
            key = idempotencyKey,
            endpoint = IdempotencyEndpoints.TRANSACTIONS,
            requestHash = requestSha256(rawBody),
        ) {
            val id = store.insertTransaction(command, normalizeMerchant(command.merchantRaw))
            StoredHttpResponse(
                status = 201,
                body = json.writeValueAsString(TransactionCreatedResponse(id, UNMATCHED)),
                replay = false,
            )
        }

    fun createDocument(
        idempotencyKey: String,
        rawBody: ByteArray,
        command: IngestDocumentCommand,
    ): StoredHttpResponse =
        idempotency.execute(
            key = idempotencyKey,
            endpoint = IdempotencyEndpoints.DOCUMENTS,
            requestHash = requestSha256(rawBody),
        ) {
            val id =
                store.insertDocument(
                    command,
                    normalizeMerchant(command.merchantRaw),
                    normalizeDocumentNumber(command.documentNumber),
                )
            StoredHttpResponse(
                status = 201,
                body = json.writeValueAsString(DocumentCreatedResponse(id)),
                replay = false,
            )
        }
}
