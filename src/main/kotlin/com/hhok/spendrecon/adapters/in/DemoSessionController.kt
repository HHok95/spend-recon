package com.hhok.spendrecon.adapters.`in`

import com.hhok.spendrecon.SpendReconProperties
import com.hhok.spendrecon.application.DemoSessionService
import io.swagger.v3.oas.annotations.Operation
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime
import java.util.UUID

@RestController
class DemoSessionController(
    private val sessions: DemoSessionService,
    private val properties: SpendReconProperties,
) {
    @PostMapping("/demo/session")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Open an isolated demo tenant")
    fun open(): DemoSessionResponse {
        if (!properties.demo.enabled) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND)
        }
        val opened = sessions.open()
        return DemoSessionResponse(
            tenantId = opened.tenantId,
            token = opened.token,
            expiresAt = opened.expiresAt,
        )
    }
}

data class DemoSessionResponse(
    val tenantId: UUID,
    val token: String,
    val expiresAt: OffsetDateTime,
)
