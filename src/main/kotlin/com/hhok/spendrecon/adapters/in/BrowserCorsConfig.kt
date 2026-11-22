package com.hhok.spendrecon.adapters.`in`

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import org.springframework.web.filter.CorsFilter

/**
 * The public demo calls this API from a browser. Reads were already open.
 * Writes still require X-API-Key or the demo session the sandbox will issue.
 */
@Configuration
class BrowserCorsConfig {
    @Bean
    fun browserCorsFilter(): CorsFilter {
        val cors =
            CorsConfiguration().apply {
                allowedOriginPatterns =
                    listOf(
                        "https://spend.hokheng.com",
                        "http://localhost:*",
                        "http://127.0.0.1:*",
                    )
                allowedMethods = listOf("GET", "HEAD", "POST", "OPTIONS")
                allowedHeaders =
                    listOf(
                        "Accept",
                        "Authorization",
                        "Content-Type",
                        "Idempotency-Key",
                        "X-API-Key",
                        "X-Demo-Session",
                    )
                exposedHeaders = listOf("Retry-After")
                maxAge = 3600
            }
        val source =
            UrlBasedCorsConfigurationSource().apply {
                registerCorsConfiguration("/**", cors)
            }
        return CorsFilter(source)
    }
}
