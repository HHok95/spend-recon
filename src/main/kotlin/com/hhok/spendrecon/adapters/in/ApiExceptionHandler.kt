package com.hhok.spendrecon.adapters.`in`

import com.fasterxml.jackson.databind.ObjectMapper
import com.hhok.spendrecon.ReconProperties
import com.hhok.spendrecon.application.IdempotencyInFlightException
import com.hhok.spendrecon.application.IdempotencyMismatchException
import com.hhok.spendrecon.domain.InvalidMoneyException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class ApiExceptionHandler(
    private val json: ObjectMapper,
    private val properties: ReconProperties,
) {
    @ExceptionHandler(InvalidRequestException::class, InvalidMoneyException::class)
    fun badRequest(exception: RuntimeException): ResponseEntity<String> =
        problemResponse(json, HttpStatus.BAD_REQUEST, exception.message ?: "Invalid request.")

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(): ResponseEntity<String> =
        problemResponse(json, HttpStatus.BAD_REQUEST, "Request body must be a JSON object.")

    @ExceptionHandler(IdempotencyMismatchException::class)
    fun mismatch(): ResponseEntity<String> =
        problemResponse(
            json,
            HttpStatus.UNPROCESSABLE_ENTITY,
            "This Idempotency-Key was already used with a different request body.",
        )

    @ExceptionHandler(IdempotencyInFlightException::class)
    fun inFlight(): ResponseEntity<String> {
        val response =
            problemResponse(
                json,
                HttpStatus.CONFLICT,
                "A request with this Idempotency-Key is still in flight.",
            )
        return ResponseEntity
            .status(response.statusCode)
            .headers(response.headers)
            .header(HttpHeaders.RETRY_AFTER, properties.idempotency.retryAfter.toSeconds().toString())
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(response.body)
    }
}
