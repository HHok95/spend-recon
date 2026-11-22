package com.hhok.spendrecon.adapters.out

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface CardTransactionRepository : JpaRepository<CardTransactionEntity, UUID> {
    fun findByTenantIdOrderByCreatedAtAscIdAsc(tenantId: UUID): List<CardTransactionEntity>

    fun findByIdAndTenantId(
        id: UUID,
        tenantId: UUID,
    ): java.util.Optional<CardTransactionEntity>
}

interface DocumentRepository : JpaRepository<DocumentEntity, UUID> {
    fun findByTenantIdOrderByCreatedAtAscIdAsc(tenantId: UUID): List<DocumentEntity>
}
