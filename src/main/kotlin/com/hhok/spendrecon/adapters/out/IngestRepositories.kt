package com.hhok.spendrecon.adapters.out

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface CardTransactionRepository : JpaRepository<CardTransactionEntity, UUID> {
    fun findByTenantIdOrderByCreatedAtDescIdDesc(
        tenantId: UUID,
        pageable: Pageable,
    ): Page<CardTransactionEntity>

    fun findByIdAndTenantId(
        id: UUID,
        tenantId: UUID,
    ): java.util.Optional<CardTransactionEntity>
}

interface DocumentRepository : JpaRepository<DocumentEntity, UUID> {
    fun findByTenantIdOrderByCreatedAtDescIdDesc(
        tenantId: UUID,
        pageable: Pageable,
    ): Page<DocumentEntity>
}
