package com.hhok.spendrecon.adapters.out

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import com.hhok.spendrecon.Tenants
import java.io.Serializable
import java.time.OffsetDateTime
import java.util.Objects
import java.util.UUID

@Embeddable
class IdempotencyKeyId(
    @Column(name = "tenant_id", nullable = false)
    var tenantId: UUID = Tenants.LOCAL,
    @Column(name = "key", nullable = false)
    var key: String = "",
    @Column(name = "endpoint", nullable = false)
    var endpoint: String = "",
) : Serializable {
    override fun equals(other: Any?): Boolean =
        other is IdempotencyKeyId && tenantId == other.tenantId && key == other.key && endpoint == other.endpoint

    override fun hashCode(): Int = Objects.hash(tenantId, key, endpoint)
}

/**
 * Mapped so Hibernate's schema check covers this table. Writes go through
 * [JdbcIdempotencyStore] because the claim is INSERT ... ON CONFLICT DO NOTHING.
 */
@Entity
@Table(name = "idempotency_keys")
class IdempotencyKeyEntity(
    @EmbeddedId
    var id: IdempotencyKeyId = IdempotencyKeyId(),
    @Column(name = "request_hash", nullable = false)
    var requestHash: String = "",
    @Column(name = "response_status")
    var responseStatus: Int? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_body")
    var responseBody: String? = null,
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    var createdAt: OffsetDateTime? = null,
)
