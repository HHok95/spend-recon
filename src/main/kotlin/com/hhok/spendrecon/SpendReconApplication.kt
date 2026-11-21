package com.hhok.spendrecon

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class SpendReconApplication

fun main(args: Array<String>) {
    runApplication<SpendReconApplication>(*args)
}
