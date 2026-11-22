package com.hhok.spendrecon.adapters.out

import com.hhok.spendrecon.CurrentTenant
import com.hhok.spendrecon.application.IngestDocumentCommand
import com.hhok.spendrecon.application.IngestStore
import com.hhok.spendrecon.application.IngestTransactionCommand
import com.hhok.spendrecon.application.UNMATCHED
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class JpaIngestStore(
    private val transactions: CardTransactionRepository,
    private val documents: DocumentRepository,
    private val tenants: CurrentTenant,
) : IngestStore {
    override fun insertTransaction(
        command: IngestTransactionCommand,
        merchantNorm: String,
    ): UUID {
        val id = UUID.randomUUID()
        transactions.saveAndFlush(
            CardTransactionEntity(
                id = id,
                tenantId = tenants.id(),
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
    ): UUID {
        val id = UUID.randomUUID()
        documents.saveAndFlush(
            DocumentEntity(
                id = id,
                tenantId = tenants.id(),
                kind = command.kind.name,
                cardOwnerId = command.cardOwnerId,
                merchantRaw = command.merchantRaw,
                merchantNorm = merchantNorm,
                amountMinor = command.amount.minor,
                currency = command.amount.currency.code,
                taxMinor = command.tax?.minor,
                docDate = command.docDate,
            ),
        )
        return id
    }
}
