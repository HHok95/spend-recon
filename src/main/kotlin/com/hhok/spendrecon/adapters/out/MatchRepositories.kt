package com.hhok.spendrecon.adapters.out

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional
import java.util.UUID

interface MatchRepository : JpaRepository<MatchEntity, UUID> {
    fun findByTenantId(
        tenantId: UUID,
        pageable: Pageable,
    ): Page<MatchEntity>

    fun findByTenantIdAndStatus(
        tenantId: UUID,
        status: String,
        pageable: Pageable,
    ): Page<MatchEntity>

    fun findByIdAndTenantId(
        id: UUID,
        tenantId: UUID,
    ): Optional<MatchEntity>

    fun findByTransactionIdAndTenantId(
        transactionId: UUID,
        tenantId: UUID,
    ): Optional<MatchEntity>
}

interface ReconciliationEventRepository : JpaRepository<ReconciliationEventEntity, Long> {
    fun findByMatchIdOrderByIdAsc(matchId: UUID): List<ReconciliationEventEntity>
}
