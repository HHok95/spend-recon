package com.hhok.spendrecon

import org.springframework.beans.factory.config.BeanFactoryPostProcessor
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.context.EnvironmentAware
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.stereotype.Component

/**
 * Prod has no local database password and no local API key.
 * A missing value stops startup before the pool opens.
 * A test that activates prod and sets its own values can still start.
 */
@Component
class ProdProfileGuard : BeanFactoryPostProcessor, EnvironmentAware {
    private lateinit var environment: Environment

    override fun setEnvironment(environment: Environment) {
        this.environment = environment
    }

    override fun postProcessBeanFactory(beanFactory: ConfigurableListableBeanFactory) {
        if (!environment.acceptsProfiles(Profiles.of("prod"))) {
            return
        }
        val apiKey = resolve("recon.api-key", "RECON_API_KEY is required")
        require(apiKey != LOCAL_API_KEY && !apiKey.contains("\${")) { "RECON_API_KEY is required" }
        val url = resolve("spring.datasource.url", "SPRING_DATASOURCE_URL is required")
        require(url.startsWith("jdbc:") && !url.contains("localhost:5432/spendrecon")) {
            "SPRING_DATASOURCE_URL is required"
        }
        resolve("spring.datasource.username", "SPRING_DATASOURCE_USERNAME is required")
        resolve("spring.datasource.password", "SPRING_DATASOURCE_PASSWORD is required")
    }

    private fun resolve(
        name: String,
        message: String,
    ): String {
        val value =
            try {
                environment.getProperty(name)
            } catch (ex: Exception) {
                throw IllegalStateException(message, ex)
            }
        require(!value.isNullOrBlank()) { message }
        return value
    }

    private companion object {
        const val LOCAL_API_KEY = "local-dev-key"
    }
}
