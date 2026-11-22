package com.hhok.spendrecon.adapters.out

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import com.hhok.spendrecon.Tenants
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Mapped so Hibernate's schema check covers matches, including the text[] rules
 * and the numeric(4,3) score. @Version is the optimistic lock for approve and reject.
 */
@Entity
@Table(name = "matches")
class MatchEntity(
    @Id
    var id: UUID = UUID.randomUUID(),
    @Column(name = "tenant_id", nullable = false)
    var tenantId: UUID = Tenants.LOCAL,
    @Column(name = "transaction_id", nullable = false)
    var transactionId: UUID = UUID.randomUUID(),
    @Column(name = "document_id")
    var documentId: UUID? = null,
    @Column(name = "status", nullable = false)
    var status: String = "",
    @Column(name = "score", precision = 4, scale = 3)
    var score: BigDecimal? = null,
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "rules_fired", nullable = false)
    var rulesFired: MutableList<String> = mutableListOf(),
    @Column(name = "exception_type")
    var exceptionType: String? = null,
    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0,
    @Column(name = "decided_by")
    var decidedBy: String? = null,
    @Column(name = "decided_at")
    var decidedAt: OffsetDateTime? = null,
    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now(),
)
