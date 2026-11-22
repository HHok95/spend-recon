package com.hhok.spendrecon.adapters.`in`

import com.fasterxml.jackson.databind.ObjectMapper
import com.hhok.spendrecon.CurrentTenant
import com.hhok.spendrecon.SpendReconProperties
import com.hhok.spendrecon.application.DemoSessionService
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.nio.charset.StandardCharsets

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
class DemoSessionFilter(
    private val properties: SpendReconProperties,
    private val sessions: DemoSessionService,
    private val tenants: CurrentTenant,
    private val json: ObjectMapper,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !properties.demo.enabled || request.method.equals("OPTIONS", ignoreCase = true)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val path = servletPath(request)
        if (isDemoPublic(path)) {
            filterChain.doFilter(request, response)
            return
        }
        val token = bearerToken(request)
        if (token == null) {
            unauthorized(response, "A valid Authorization: Bearer token is required.")
            return
        }
        val hit = sessions.lookup(token)
        if (hit == null) {
            unauthorized(response, "This demo session is not valid.")
            return
        }
        if (!hit.active) {
            unauthorized(response, "This demo session has expired.")
            return
        }
        request.setAttribute(AUTHENTICATED, true)
        tenants.set(hit.tenantId)
        try {
            filterChain.doFilter(request, response)
        } finally {
            tenants.clear()
        }
    }

    private fun unauthorized(
        response: HttpServletResponse,
        detail: String,
    ) {
        response.status = HttpStatus.UNAUTHORIZED.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.characterEncoding = StandardCharsets.UTF_8.name()
        response.writer.write(problemJson(json, HttpStatus.UNAUTHORIZED, detail))
    }

    companion object {
        const val AUTHENTICATED = "spendrecon.demo.authenticated"
    }
}
