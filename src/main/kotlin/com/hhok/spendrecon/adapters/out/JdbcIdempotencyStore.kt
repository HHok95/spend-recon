package com.hhok.spendrecon.adapters.out

import com.hhok.spendrecon.ReconProperties
import com.hhok.spendrecon.application.IdempotencyInFlightException
import com.hhok.spendrecon.application.IdempotencyRecord
import com.hhok.spendrecon.application.IdempotencyStore
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.SQLException

@Repository
class JdbcIdempotencyStore(
    private val jdbc: JdbcTemplate,
    private val properties: ReconProperties,
) : IdempotencyStore {
    override fun insertIfAbsent(
        key: String,
        endpoint: String,
        requestHash: String,
    ): Boolean {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "The idempotency insert runs in the same transaction as the business write."
        }
        // A concurrent insert holds this primary key until it commits the response.
        // lock_timeout stops the wait and the caller returns 409. After the first
        // transaction commits, this insert affects zero rows and the caller replays.
        jdbc.execute("SET LOCAL lock_timeout = '${lockTimeoutMillis()}ms'")
        try {
            val inserted =
                jdbc.update(
                    """
                    INSERT INTO idempotency_keys (key, endpoint, request_hash)
                    VALUES (?, ?, ?)
                    ON CONFLICT (key, endpoint) DO NOTHING
                    """.trimIndent(),
                    key,
                    endpoint,
                    requestHash,
                )
            jdbc.execute("SET LOCAL lock_timeout = 0")
            return inserted == 1
        } catch (exception: DataAccessException) {
            if (isLockTimeout(exception)) {
                throw IdempotencyInFlightException()
            }
            throw exception
        }
    }

    override fun find(
        key: String,
        endpoint: String,
    ): IdempotencyRecord? =
        jdbc
            .query(
                """
                SELECT request_hash, response_status, response_body
                FROM idempotency_keys
                WHERE key = ? AND endpoint = ?
                """.trimIndent(),
                { rs, _ ->
                    IdempotencyRecord(
                        requestHash = rs.getString("request_hash"),
                        responseStatus = rs.getObject("response_status")?.let { (it as Number).toInt() },
                        responseBody = rs.getString("response_body"),
                    )
                },
                key,
                endpoint,
            ).firstOrNull()

    override fun storeResponse(
        key: String,
        endpoint: String,
        status: Int,
        body: String,
    ): String {
        val updated =
            jdbc.update(
                """
                UPDATE idempotency_keys
                SET response_status = ?, response_body = ?::jsonb
                WHERE key = ? AND endpoint = ?
                """.trimIndent(),
                status,
                body,
                key,
                endpoint,
            )
        check(updated == 1) { "Idempotency response was not stored for $endpoint." }
        return jdbc.queryForObject(
            """
            SELECT response_body
            FROM idempotency_keys
            WHERE key = ? AND endpoint = ?
            """.trimIndent(),
            String::class.java,
            key,
            endpoint,
        ) ?: error("Idempotency response body was empty after it was stored.")
    }

    private fun lockTimeoutMillis(): Long {
        val millis = properties.idempotency.lockTimeout.toMillis()
        check(millis in 1..120_000) { "lock timeout is not a safe SQL literal." }
        return millis
    }
}

internal fun isLockTimeout(error: Throwable): Boolean {
    var current: Throwable? = error
    while (current != null) {
        if (current is SQLException && current.sqlState == "55P03") {
            return true
        }
        if (current.message.orEmpty().contains("lock timeout", ignoreCase = true)) {
            return true
        }
        current = current.cause
    }
    return false
}
