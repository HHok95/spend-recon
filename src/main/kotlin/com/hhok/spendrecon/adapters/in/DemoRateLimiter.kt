package com.hhok.spendrecon.adapters.`in`

import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

data class RateDecision(
    val allowed: Boolean,
    val retryAfterSeconds: Long,
)

/**
 * One process, one map. A second machine keeps its own counters.
 * That matches a single 512 MB machine. Do not treat this as a shared limit.
 */
@Component
class DemoRateLimiter {
    private val buckets = ConcurrentHashMap<String, Bucket>()

    fun tryAcquire(
        key: String,
        capacity: Int,
        window: Duration,
    ): RateDecision {
        val now = System.nanoTime()
        val windowNanos = window.toNanos().coerceAtLeast(1L)
        val refillPerNano = capacity.toDouble() / windowNanos.toDouble()
        val updated =
            buckets.compute(key) { _, existing ->
                val tokens =
                    if (existing == null) {
                        capacity.toDouble()
                    } else {
                        val elapsed = (now - existing.updatedAt).coerceAtLeast(0L)
                        (existing.tokens + elapsed * refillPerNano).coerceAtMost(capacity.toDouble())
                    }
                if (tokens >= 1.0) {
                    Bucket(tokens - 1.0, now, allowed = true, retryAfterSeconds = 0)
                } else {
                    val waitNanos = (1.0 - tokens) / refillPerNano
                    val seconds = ceil(waitNanos / 1_000_000_000.0).toLong().coerceAtLeast(1L)
                    Bucket(tokens, now, allowed = false, retryAfterSeconds = seconds)
                }
            }!!
        if (buckets.size > 4_096) {
            buckets.entries.removeIf { now - it.value.updatedAt > windowNanos && it.value.tokens >= capacity }
        }
        return RateDecision(updated.allowed, updated.retryAfterSeconds)
    }

    fun clear() {
        buckets.clear()
    }

    private data class Bucket(
        val tokens: Double,
        val updatedAt: Long,
        val allowed: Boolean,
        val retryAfterSeconds: Long,
    )
}
