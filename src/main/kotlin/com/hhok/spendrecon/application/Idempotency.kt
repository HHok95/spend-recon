package com.hhok.spendrecon.application

/** One stored idempotency row. A null response means the original request has not finished. */
data class IdempotencyRecord(
    val requestHash: String,
    val responseStatus: Int?,
    val responseBody: String?,
)

/** HTTP status and body to return, and whether this call replayed a stored response. */
data class StoredHttpResponse(
    val status: Int,
    val body: String,
    val replay: Boolean,
)

object IdempotencyEndpoints {
    const val TRANSACTIONS = "POST /transactions"
    const val DOCUMENTS = "POST /documents"

    fun reconcile(transactionId: java.util.UUID): String = "POST /reconcile/$transactionId"
}

/** The key's response is not stored yet. The HTTP layer answers 409 with Retry-After. */
class IdempotencyInFlightException : RuntimeException()

/** The key was already stored for a different request body. The HTTP layer answers 422. */
class IdempotencyMismatchException : RuntimeException()

/**
 * Persistence for [IdempotencyService].
 * The service calls these methods inside one database transaction.
 */
interface IdempotencyStore {
    /**
     * Inserts the key when it is new.
     * Returns false when the key is already stored.
     * Throws [IdempotencyInFlightException] when another transaction holds the key
     * and does not release it before the lock timeout.
     */
    fun insertIfAbsent(
        key: String,
        endpoint: String,
        requestHash: String,
    ): Boolean

    fun find(
        key: String,
        endpoint: String,
    ): IdempotencyRecord?

    /**
     * Stores the finished response and returns the JSON text the database kept.
     * Callers return that text so a replay is byte-for-byte the first response.
     */
    fun storeResponse(
        key: String,
        endpoint: String,
        status: Int,
        body: String,
    ): String
}
