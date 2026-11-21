package com.hhok.spendrecon

import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * One Postgres 16 database for every Spring test in this JVM.
 * Each test class points Spring's datasource at it.
 */
abstract class PostgresIntegrationTest {
    companion object {
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine")).apply {
                withDatabaseName("spendrecon")
                withUsername("spendrecon")
                withPassword("spendrecon")
                // Several Spring contexts each keep a pool. The image default is 100.
                withCommand("postgres", "-c", "max_connections=300")
                start()
            }

        @DynamicPropertySource
        @JvmStatic
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
