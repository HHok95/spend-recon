package com.hhok.spendrecon

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Duration

@SpringBootTest(
    properties = [
        "recon.api-key=test-key",
        "spring.datasource.hikari.data-source-properties.sslmode=disable",
    ],
)
@ActiveProfiles("prod")
class DemoProdProfileTest : PostgresIntegrationTest() {
    @Autowired
    private lateinit var properties: SpendReconProperties

    @Test
    fun `prod turns the demo on and allows only the spend origin`() {
        assertThat(properties.demo.enabled).isTrue()
        assertThat(properties.demo.sessionTtl).isEqualTo(Duration.ofHours(1))
        assertThat(properties.demo.sessionCreatesPerHour).isEqualTo(10)
        assertThat(properties.demo.requestsPerMinute).isEqualTo(60)
        assertThat(properties.cors.allowedOriginPatterns).containsExactly("https://spend.hokheng.com")
    }
}

@SpringBootTest
@ActiveProfiles("dev")
class DemoDevProfileTest : PostgresIntegrationTest() {
    @Autowired
    private lateinit var properties: SpendReconProperties

    @Test
    fun `dev keeps the demo off and allows local browsers`() {
        assertThat(properties.demo.enabled).isFalse()
        assertThat(properties.cors.allowedOriginPatterns).containsExactly(
            "http://localhost:*",
            "http://127.0.0.1:*",
        )
    }
}
