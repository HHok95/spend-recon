package com.hhok.spendrecon.adapters.out

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import com.hhok.spendrecon.Tenants
import java.util.UUID

@Entity
@Table(name = "documents")
class DocumentEntity(
    @Id
    var id: UUID = UUID.randomUUID(),
    @Column(name = "tenant_id", nullable = false)
    var tenantId: UUID = Tenants.LOCAL,
    @Column(name = "kind", nullable = false)
    var kind: String = "",
    @Column(name = "card_owner_id", nullable = false)
    var cardOwnerId: String = "",
    @Column(name = "merchant_raw", nullable = false)
    var merchantRaw: String = "",
    @Column(name = "merchant_norm", nullable = false)
    var merchantNorm: String = "",
    @Column(name = "amount_minor", nullable = false)
    var amountMinor: Long = 0,
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3)
    var currency: String = "",
    @Column(name = "tax_minor")
    var taxMinor: Long? = null,
    @Column(name = "doc_date", nullable = false)
    var docDate: LocalDate = LocalDate.EPOCH,
    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC),
)
