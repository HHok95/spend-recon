package com.hhok.spendrecon.adapters.`in`

import com.fasterxml.jackson.databind.ObjectMapper
import com.hhok.spendrecon.SpendReconProperties
import com.hhok.spendrecon.application.requestSha256
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.nio.charset.StandardCharsets
import java.time.Duration

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
class DemoRateLimitFilter(
    private val properties: SpendReconProperties,
    private val limiter: DemoRateLimiter,
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
        if (request.method.equals("POST", ignoreCase = true) && path == "/demo/session") {
            val decision =
                limiter.tryAcquire(
                    "ip:${clientAddress(request)}",
                    properties.demo.sessionCreatesPerHour,
                    Duration.ofHours(1),
                )
            if (!decision.allowed) {
                tooMany(response, "Too many demo sessions from this address.", decision.retryAfterSeconds)
                return
            }
        } else if (!isDemoPublic(path)) {
            val token = bearerToken(request)
            if (token != null) {
                val decision =
                    limiter.tryAcquire(
                        "token:${requestSha256(token.toByteArray(StandardCharsets.UTF_8))}",
                        properties.demo.requestsPerMinute,
                        Duration.ofMinutes(1),
                    )
                if (!decision.allowed) {
                    tooMany(response, "Too many requests for this demo session.", decision.retryAfterSeconds)
                    return
                }
            }
        }
        filterChain.doFilter(request, response)
    }

    private fun tooMany(
        response: HttpServletResponse,
        detail: String,
        retryAfterSeconds: Long,
    ) {
        response.status = HttpStatus.TOO_MANY_REQUESTS.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.characterEncoding = StandardCharsets.UTF_8.name()
        response.setHeader(HttpHeaders.RETRY_AFTER, retryAfterSeconds.toString())
        response.writer.write(problemJson(json, HttpStatus.TOO_MANY_REQUESTS, detail))
    }
}
