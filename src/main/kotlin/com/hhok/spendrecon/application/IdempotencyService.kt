package com.hhok.spendrecon.application

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class IdempotencyService(
    private val store: IdempotencyStore,
    meterRegistry: MeterRegistry,
) {
    private val replays =
        Counter
            .builder("idempotent_replays")
            .description("Stored responses returned for a repeated Idempotency-Key")
            .register(meterRegistry)

    /**
     * Runs [action] only for the request that inserts the key.
     * The insert, the business write inside [action], and the stored response commit
     * or roll back together. A second call with the same key does not run [action].
     */
    @Transactional
    fun execute(
        key: String,
        endpoint: String,
        requestHash: String,
        action: () -> StoredHttpResponse,
    ): StoredHttpResponse {
        val inserted = store.insertIfAbsent(key, endpoint, requestHash)
        if (!inserted) {
            return replay(key, endpoint, requestHash)
        }
        val created = action()
        val storedBody = store.storeResponse(key, endpoint, created.status, created.body)
        return StoredHttpResponse(status = created.status, body = storedBody, replay = false)
    }

    private fun replay(
        key: String,
        endpoint: String,
        requestHash: String,
    ): StoredHttpResponse {
        val existing =
            store.find(key, endpoint)
                ?: error("Idempotency key for $endpoint disappeared after the insert conflict.")
        if (existing.requestHash != requestHash) {
            throw IdempotencyMismatchException()
        }
        val status = existing.responseStatus
        val body = existing.responseBody
        if (status == null || body == null) {
            throw IdempotencyInFlightException()
        }
        replays.increment()
        return StoredHttpResponse(status = status, body = body, replay = true)
    }
}
