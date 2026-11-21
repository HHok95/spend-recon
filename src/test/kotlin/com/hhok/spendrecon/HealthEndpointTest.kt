package com.hhok.spendrecon

import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
// Spring Boot tests turn metric exporters off. This opts back in so the scrape endpoint is mapped.
@AutoConfigureObservability
class HealthEndpointTest : PostgresIntegrationTest() {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `health endpoint reports up`() {
        mockMvc
            .perform(get("/actuator/health"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("UP"))
    }

    @Test
    fun `prometheus registry is exposed`() {
        mockMvc
            .perform(get("/actuator/prometheus"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("jvm_")))
    }

    @Test
    fun `scalar and the openapi document are served`() {
        mockMvc
            .perform(get("/v3/api-docs"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.info.title").value("spend-recon"))

        mockMvc
            .perform(get("/scalar"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("/v3/api-docs")))
            .andExpect(content().string(containsString("Scalar")))

        mockMvc
            .perform(get("/swagger-ui/index.html"))
            .andExpect(status().isNotFound)
    }
}
