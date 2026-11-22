package com.hhok.spendrecon.adapters.out

import com.hhok.spendrecon.application.DuplicateDocumentException
import com.hhok.spendrecon.application.IngestDocumentCommand
import com.hhok.spendrecon.application.IngestStore
import com.hhok.spendrecon.application.IngestTransactionCommand
import com.hhok.spendrecon.application.UNMATCHED
import org.hibernate.exception.ConstraintViolationException
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class JpaIngestStore(
    private val transactions: CardTransactionRepository,
    private val documents: DocumentRepository,
) : IngestStore {
    override fun insertTransaction(
        command: IngestTransactionCommand,
        merchantNorm: String,
    ): UUID {
        val id = UUID.randomUUID()
        transactions.saveAndFlush(
            CardTransactionEntity(
                id = id,
                cardOwnerId = command.cardOwnerId,
                merchantRaw = command.merchantRaw,
                merchantNorm = merchantNorm,
                amountMinor = command.amount.minor,
                currency = command.amount.currency.code,
                taxMinor = command.tax?.minor,
                txnDate = command.txnDate,
                status = UNMATCHED,
            ),
        )
        return id
    }

    override fun insertDocument(
        command: IngestDocumentCommand,
        merchantNorm: String,
        documentNumberNorm: String?,
    ): UUID {
        val id = UUID.randomUUID()
        try {
            documents.saveAndFlush(
                DocumentEntity(
                    id = id,
                    kind = command.kind.name,
                    cardOwnerId = command.cardOwnerId,
                    merchantRaw = command.merchantRaw,
                    merchantNorm = merchantNorm,
                    amountMinor = command.amount.minor,
                    currency = command.amount.currency.code,
                    taxMinor = command.tax?.minor,
                    docDate = command.docDate,
                    documentNumber = command.documentNumber,
                    documentNumberNorm = documentNumberNorm,
                ),
            )
        } catch (exception: RuntimeException) {
            if (exception.causedByDuplicateDocument()) {
                throw DuplicateDocumentException()
            }
            throw exception
        }
        return id
    }
}

private const val DUPLICATE_DOCUMENT_INDEX = "documents_merchant_document_number"

/** True when Postgres rejected the exact merchant and receipt-number key. */
private fun Throwable.causedByDuplicateDocument(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is ConstraintViolationException && current.constraintName == DUPLICATE_DOCUMENT_INDEX) {
            return true
        }
        if (current.message?.contains(DUPLICATE_DOCUMENT_INDEX) == true) {
            return true
        }
        current = current.cause
    }
    return false
}
