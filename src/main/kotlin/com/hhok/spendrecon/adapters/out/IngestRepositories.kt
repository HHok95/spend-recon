package com.hhok.spendrecon.adapters.out

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface CardTransactionRepository : JpaRepository<CardTransactionEntity, UUID>

interface DocumentRepository : JpaRepository<DocumentEntity, UUID>
