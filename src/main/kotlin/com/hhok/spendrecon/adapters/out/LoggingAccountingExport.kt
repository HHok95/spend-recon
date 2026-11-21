package com.hhok.spendrecon.adapters.out

import com.hhok.spendrecon.application.AccountingExportPort
import com.hhok.spendrecon.application.OutboxEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Logging stand-in for the accounting export. The set of accepted ids is the
 * consumer's de-dupe record. It lives in this process: a restart can log an id
 * again, which is the at-least-once case the poller is allowed to produce.
 */
@Component
class LoggingAccountingExport : AccountingExportPort {
    private val log = LoggerFactory.getLogger(javaClass)
    private val calls = ConcurrentHashMap<Long, AtomicInteger>()
    private val accepted = ConcurrentHashMap.newKeySet<Long>()

    override fun publish(event: OutboxEvent) {
        calls.computeIfAbsent(event.id) { AtomicInteger() }.incrementAndGet()
        if (!accepted.add(event.id)) {
            log.info("outbox id {} already accepted, skipping", event.id)
            return
        }
        log.info(
            "accounting export outbox id {} type {} aggregate {}",
            event.id,
            event.eventType,
            event.aggregateId,
        )
    }

    fun callsFor(id: Long): Int = calls[id]?.get() ?: 0

    fun hasAccepted(id: Long): Boolean = id in accepted

    fun acceptedCount(): Int = accepted.size
}
