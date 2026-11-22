package com.hhok.spendrecon

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "spendrecon")
data class SpendReconProperties(
    val demo: Demo = Demo(),
    val cors: Cors = Cors(),
) {
    init {
        require(!demo.sessionTtl.isNegative && !demo.sessionTtl.isZero) {
            "spendrecon.demo.session-ttl must be positive."
        }
        require(demo.sessionTtl <= Duration.ofDays(7)) {
            "spendrecon.demo.session-ttl must be at most 7 days."
        }
        require(demo.cleanupInterval >= Duration.ofSeconds(1)) {
            "spendrecon.demo.cleanup-interval must be at least 1 second."
        }
        require(demo.sessionCreatesPerHour in 1..10_000) {
            "spendrecon.demo.session-creates-per-hour must be from 1 to 10000."
        }
        require(demo.requestsPerMinute in 1..10_000) {
            "spendrecon.demo.requests-per-minute must be from 1 to 10000."
        }
    }

    data class Demo(
        val enabled: Boolean = false,
        val sessionTtl: Duration = Duration.ofHours(1),
        val cleanupInterval: Duration = Duration.ofMinutes(1),
        val sessionCreatesPerHour: Int = 10,
        val requestsPerMinute: Int = 60,
    )

    data class Cors(
        val allowedOriginPatterns: List<String> = emptyList(),
    )
}
