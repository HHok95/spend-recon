package com.hhok.spendrecon.adapters.`in`

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * OpenAPI document metadata. Scalar renders this document at /scalar.
 * Write operations declare the apiKey scheme themselves. Reads stay open.
 */
@Configuration
class OpenApiConfig {
    @Bean
    fun openApi(): OpenAPI =
        OpenAPI()
            .info(
                Info()
                    .title("spend-recon")
                    .description("Card transaction to receipt reconciliation.")
                    .version("0.0.1"),
            ).components(
                Components().addSecuritySchemes(
                    "apiKey",
                    SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .`in`(SecurityScheme.In.HEADER)
                        .name("X-API-Key"),
                ),
            )
}
