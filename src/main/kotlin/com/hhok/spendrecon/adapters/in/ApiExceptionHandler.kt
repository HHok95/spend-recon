package com.hhok.spendrecon.adapters.`in`

import com.fasterxml.jackson.databind.ObjectMapper
import com.hhok.spendrecon.ReconProperties
import com.hhok.spendrecon.application.IdempotencyInFlightException
import com.hhok.spendrecon.application.IdempotencyMismatchException
import com.hhok.spendrecon.application.IllegalTransitionException
import com.hhok.spendrecon.application.MatchNotFoundException
import com.hhok.spendrecon.application.OverrideReasonRequiredException
import com.hhok.spendrecon.application.StaleVersionException
import com.hhok.spendrecon.application.TransactionAlreadyMatchedException
import com.hhok.spendrecon.application.TransactionNotFoundException
import com.hhok.spendrecon.application.causedByOptimisticLock
import com.hhok.spendrecon.domain.InvalidMoneyException
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import jakarta.persistence.OptimisticLockException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.transaction.TransactionSystemException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class ApiExceptionHandler(
    private val json: ObjectMapper,
    private val properties: ReconProperties,
    meterRegistry: MeterRegistry,
) {
    private val conflicts =
        Counter
            .builder("optimistic_lock_conflicts")
            .description("Approve or reject calls whose version was not the row's current version")
            .register(meterRegistry)
    @ExceptionHandler(InvalidRequestException::class, InvalidMoneyException::class)
    fun badRequest(exception: RuntimeException): ResponseEntity<String> =
        problemResponse(json, HttpStatus.BAD_REQUEST, exception.message ?: "Invalid request.")

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(): ResponseEntity<String> =
        problemResponse(json, HttpStatus.BAD_REQUEST, "Request body must be a JSON object.")

    @ExceptionHandler(TransactionNotFoundException::class, MatchNotFoundException::class)
    fun notFound(exception: RuntimeException): ResponseEntity<String> =
        problemResponse(json, HttpStatus.NOT_FOUND, exception.message ?: "Not found.")

    @ExceptionHandler(TransactionAlreadyMatchedException::class)
    fun alreadyMatched(): ResponseEntity<String> =
        problemResponse(
            json,
            HttpStatus.CONFLICT,
            "This transaction already has a match.",
        )

    @ExceptionHandler(
        StaleVersionException::class,
        OptimisticLockException::class,
        OptimisticLockingFailureException::class,
    )
    fun staleVersion(): ResponseEntity<String> {
        conflicts.increment()
        return problemResponse(
            json,
            HttpStatus.CONFLICT,
            "The match version is stale. Reload the match and retry with the current version.",
        )
    }

    @ExceptionHandler(TransactionSystemException::class)
    fun commitFailed(exception: TransactionSystemException): ResponseEntity<String> {
        if (exception.causedByOptimisticLock()) {
            return staleVersion()
        }
        throw exception
    }

    @ExceptionHandler(IllegalTransitionException::class)
    fun illegalTransition(exception: IllegalTransitionException): ResponseEntity<String> =
        problemResponse(
            json,
            HttpStatus.CONFLICT,
            exception.message ?: "This match cannot change status.",
        )

    @ExceptionHandler(OverrideReasonRequiredException::class)
    fun overrideReason(exception: OverrideReasonRequiredException): ResponseEntity<String> =
        problemResponse(
            json,
            HttpStatus.UNPROCESSABLE_ENTITY,
            exception.message ?: "Approving an exception requires a non-empty reason.",
        )

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
