package com.hhok.spendrecon.adapters.`in`

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * OpenAPI document metadata. Scalar renders this document at /scalar.
 * REST controllers, DTOs, the API key filter, and problem+json error
 * mapping arrive in later pull requests.
 */
@Configuration
class OpenApiConfig {
    @Bean
    fun openApi(): OpenAPI =
        OpenAPI().info(
            Info()
                .title("spend-recon")
                .description("Card transaction to receipt reconciliation.")
                .version("0.0.1"),
        )
}
