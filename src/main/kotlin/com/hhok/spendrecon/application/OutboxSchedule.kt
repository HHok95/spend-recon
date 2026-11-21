package com.hhok.spendrecon.application

import com.hhok.spendrecon.ReconProperties
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.SchedulingConfigurer
import org.springframework.scheduling.config.ScheduledTaskRegistrar

@Configuration
@EnableScheduling
class OutboxSchedule(
    private val publisher: OutboxPublisher,
    private val properties: ReconProperties,
) : SchedulingConfigurer {
    override fun configureTasks(taskRegistrar: ScheduledTaskRegistrar) {
        taskRegistrar.addFixedDelayTask(
            Runnable {
                if (properties.outbox.enabled) {
                    publisher.publishPending()
                }
            },
            properties.outbox.pollInterval,
        )
    }
}
