package com.hhok.spendrecon.adapters.out

import com.fasterxml.jackson.databind.JsonNode
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.OffsetDateTime
import java.util.UUID

/** Append-only history. The database trigger rejects UPDATE and DELETE. */
@Entity
@Table(name = "reconciliation_events")
class ReconciliationEventEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @Column(name = "match_id", nullable = false)
    var matchId: UUID = UUID.randomUUID(),
    @Column(name = "event_type", nullable = false)
    var eventType: String = "",
    @Column(name = "actor", nullable = false)
    var actor: String = "",
    @Column(name = "reason")
    var reason: String? = null,
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "rules_fired", nullable = false)
    var rulesFired: MutableList<String> = mutableListOf(),
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before")
    var beforeState: JsonNode? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after")
    var afterState: JsonNode? = null,
    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now(),
)
