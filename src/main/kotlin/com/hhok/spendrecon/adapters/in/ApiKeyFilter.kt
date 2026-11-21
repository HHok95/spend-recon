package com.hhok.spendrecon.adapters.`in`

import com.fasterxml.jackson.databind.ObjectMapper
import com.hhok.spendrecon.ReconProperties
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Writes require [ApiHeaders.API_KEY]. Reads stay open: health, metrics, and Scalar
 * are checked by clients that do not send the key.
 */
@Component
class ApiKeyFilter(
    private val properties: ReconProperties,
    private val json: ObjectMapper,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        if (!request.method.equals("POST", ignoreCase = true)) {
            return true
        }
        val path = pathOf(request)
        if (path in PROTECTED || path == "/reconcile" || path.startsWith("/reconcile/") || isDecision(path)) {
            return false
        }
        return true
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val presented = request.getHeader(ApiHeaders.API_KEY)
        if (presented != null && constantTimeEquals(presented, properties.apiKey)) {
            filterChain.doFilter(request, response)
            return
        }
        response.status = HttpStatus.UNAUTHORIZED.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.characterEncoding = StandardCharsets.UTF_8.name()
        response.writer.write(
            problemJson(json, HttpStatus.UNAUTHORIZED, "A valid X-API-Key header is required."),
        )
    }

    private fun pathOf(request: HttpServletRequest): String = request.servletPath.ifEmpty { request.requestURI }

    private fun isDecision(path: String): Boolean {
        val normalized = path.removeSuffix("/")
        return normalized.startsWith("/matches/") &&
            (normalized.endsWith("/approve") || normalized.endsWith("/reject"))
    }

    companion object {
        private val PROTECTED = setOf("/transactions", "/documents")
    }
}

/** Equal-length keys take the same time to compare. A different length still returns false. */
internal fun constantTimeEquals(
    left: String,
    right: String,
): Boolean =
    MessageDigest.isEqual(
        left.toByteArray(StandardCharsets.UTF_8),
        right.toByteArray(StandardCharsets.UTF_8),
    )
