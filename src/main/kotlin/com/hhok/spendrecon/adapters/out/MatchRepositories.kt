package com.hhok.spendrecon.adapters.out

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface MatchRepository : JpaRepository<MatchEntity, UUID> {
    fun existsByTransactionId(transactionId: UUID): Boolean

    fun findByStatus(
        status: String,
        pageable: Pageable,
    ): Page<MatchEntity>
}

interface ReconciliationEventRepository : JpaRepository<ReconciliationEventEntity, Long> {
    fun findByMatchIdOrderByIdAsc(matchId: UUID): List<ReconciliationEventEntity>
}
