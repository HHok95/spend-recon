package com.hhok.spendrecon

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable
import org.springframework.boot.SpringApplication

class ProdProfileSecretsTest {
    @Test
    @DisabledIfEnvironmentVariable(named = "RECON_API_KEY", matches = ".+")
    fun `prod exits when the api key is missing`() {
        val keys =
            listOf(
                "SPRING_DATASOURCE_URL" to "jdbc:postgresql://127.0.0.1:1/spendrecon",
                "SPRING_DATASOURCE_USERNAME" to "spendrecon",
                "SPRING_DATASOURCE_PASSWORD" to "spendrecon",
            )
        keys.forEach { (key, value) -> System.setProperty(key, value) }
        val application = SpringApplication(SpendReconApplication::class.java)
        application.setAdditionalProfiles("prod")
        try {
            val failure =
                runCatching { application.run() }.exceptionOrNull()
                    ?: error("prod started without RECON_API_KEY")
            val messages = generateSequence(failure) { it.cause }.mapNotNull { it.message }.toList()
            assertThat(messages).anyMatch { it.contains("Could not resolve placeholder 'RECON_API_KEY'") }
        } finally {
            keys.forEach { (key, _) -> System.clearProperty(key) }
        }
    }
}
