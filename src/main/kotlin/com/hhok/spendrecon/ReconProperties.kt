package com.hhok.spendrecon

import org.springframework.boot.context.properties.ConfigurationProperties
import java.math.BigDecimal
import java.time.Duration

@ConfigurationProperties(prefix = "recon")
data class ReconProperties(
    val apiKey: String,
    val idempotency: Idempotency,
    val autoApprove: AutoApprove,
) {
    init {
        require(apiKey.isNotBlank()) { "recon.api-key must not be blank." }
        require(idempotency.lockTimeout.toMillis() in 1..120_000) {
            "recon.idempotency.lock-timeout must be between 1ms and 120s."
        }
        require(idempotency.retryAfter.toSeconds() >= 1) {
            "recon.idempotency.retry-after must be at least 1 second."
        }
        require(autoApprove.threshold >= BigDecimal.ZERO) {
            "recon.auto-approve.threshold must be zero or greater."
        }
    }

    data class Idempotency(
        val lockTimeout: Duration,
        val retryAfter: Duration,
    )

    /** A value above 1 disables auto-approve. The matched cutoff stays 0.85. */
    data class AutoApprove(
        val threshold: BigDecimal,
    )
}
