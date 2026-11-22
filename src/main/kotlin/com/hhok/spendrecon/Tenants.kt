package com.hhok.spendrecon

import org.springframework.stereotype.Component
import java.util.UUID

/** The book used when no demo session is present. Migration V5 inserts this row. */
object Tenants {
    val LOCAL: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
}

/**
 * The tenant for the current request. Empty means [Tenants.LOCAL], which is
 * what Compose, kind, and the existing tests use.
 */
@Component
class CurrentTenant {
    private val thread = ThreadLocal<UUID>()

    fun id(): UUID = thread.get() ?: Tenants.LOCAL

    fun set(id: UUID) {
        thread.set(id)
    }

    fun clear() {
        thread.remove()
    }
}
