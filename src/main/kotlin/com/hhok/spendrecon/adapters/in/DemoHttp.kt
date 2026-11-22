package com.hhok.spendrecon.adapters.`in`

import jakarta.servlet.http.HttpServletRequest

internal fun servletPath(request: HttpServletRequest): String = request.servletPath.ifEmpty { request.requestURI }

/** Health, the session opener, and the API reference stay open. Data routes do not. */
internal fun isDemoPublic(path: String): Boolean {
    if (path == "/demo/session" || path == "/scalar" || path == "/v3/api-docs") {
        return true
    }
    return path.startsWith("/v3/api-docs/") ||
        path == "/actuator" ||
        path.startsWith("/actuator/") ||
        path == "/swagger-ui" ||
        path.startsWith("/swagger-ui/")
}

/** The scheme is the literal `Bearer` prefix. Anything else is not a demo token. */
internal fun bearerToken(request: HttpServletRequest): String? {
    val header = request.getHeader("Authorization") ?: return null
    if (!header.startsWith("Bearer ")) {
        return null
    }
    val token = header.substring("Bearer ".length).trim()
    if (token.isEmpty() || token.length > 200) {
        return null
    }
    return token
}

/**
 * Fly sets Fly-Client-IP to the address that opened the connection.
 * The socket address is the fallback when that header is absent.
 */
internal fun clientAddress(request: HttpServletRequest): String {
    val fly = request.getHeader("Fly-Client-IP")?.trim()
    if (!fly.isNullOrEmpty()) {
        return fly
    }
    return request.remoteAddr ?: "unknown"
}
