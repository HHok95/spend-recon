package com.hhok.spendrecon.adapters.`in`

import com.hhok.spendrecon.SpendReconProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import org.springframework.web.filter.CorsFilter

/**
 * The only CORS filter. Origins come from `spendrecon.cors.allowed-origin-patterns`.
 * Prod allows https://spend.hokheng.com. Dev allows local browsers.
 * An empty list allows no browser origin.
 *
 * The page sends Authorization, or X-Demo-Session if that constant changes,
 * plus Idempotency-Key and Accept. It reads Retry-After.
 */
@Configuration
class BrowserCorsConfig(
    private val properties: SpendReconProperties,
) {
    @Bean
    fun browserCorsFilter(): FilterRegistrationBean<CorsFilter> {
        val patterns = properties.cors.allowedOriginPatterns.filter { it.isNotBlank() }
        val cors =
            CorsConfiguration().apply {
                allowedOriginPatterns = patterns
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
                if (patterns.isNotEmpty()) {
                    registerCorsConfiguration("/**", cors)
                }
            }
        return FilterRegistrationBean(CorsFilter(source)).apply {
            order = Ordered.HIGHEST_PRECEDENCE
        }
    }
}
