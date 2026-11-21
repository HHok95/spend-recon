package com.hhok.spendrecon.domain

import java.math.BigDecimal
import java.util.Locale

/** The API sent a currency or an amount this service will not store. */
class InvalidMoneyException(message: String) : RuntimeException(message)

/** [Money.plus] was called with two different currencies. */
class CurrencyMismatch(
    val left: Currency,
    val right: Currency,
) : RuntimeException("Cannot add ${left.code} and ${right.code}.")

/**
 * ISO-4217 currency code, stored uppercase.
 * [parse] accepts the API value and uppercases it. The constructor then checks
 * the code against [java.util.Currency], which also supplies the minor-unit scale.
 */
@JvmInline
value class Currency(val code: String) {
    init {
        if (code.length != 3 || code.any { !it.isLetter() } || code != code.uppercase(Locale.ROOT)) {
            throw InvalidMoneyException("Currency must be a 3-letter ISO-4217 code.")
        }
        val jdk =
            try {
                java.util.Currency.getInstance(code)
            } catch (_: IllegalArgumentException) {
                throw InvalidMoneyException("Unknown currency $code.")
            }
        if (jdk.defaultFractionDigits < 0) {
            throw InvalidMoneyException("Currency $code has no minor-unit scale.")
        }
    }

    /** Digits after the decimal point. JPY is 0, AUD is 2, BHD is 3. */
    val fractionDigits: Int
        get() = java.util.Currency.getInstance(code).defaultFractionDigits

    companion object {
        fun parse(raw: String): Currency = Currency(raw.trim().uppercase(Locale.ROOT))
    }
}

/**
 * A non-negative amount stored as integer minor units.
 * AUD 12.34 is 1234. JPY 500 is 500. The API never sends a JSON number.
 */
data class Money(
    val minor: Long,
    val currency: Currency,
) {
    init {
        require(minor >= 0L) { "Money minor units cannot be negative." }
    }

    operator fun plus(other: Money): Money {
        if (currency != other.currency) {
            throw CurrencyMismatch(currency, other.currency)
        }
        return Money(Math.addExact(minor, other.minor), currency)
    }

    /** Decimal string for this amount, with the currency's minor-unit scale. */
    fun toDecimalString(): String = BigDecimal.valueOf(minor, currency.fractionDigits).toPlainString()

    companion object {
        private val DECIMAL = Regex("^[0-9]+(\\.[0-9]+)?$")

        /**
         * Converts an API decimal string into minor units.
         * The scale is the number of digits the client wrote, so "12.340" AUD is
         * rejected even though the extra digit is zero. Fewer digits are allowed:
         * "12.3" AUD is 1230 minor units.
         */
        fun parse(
            amount: String,
            currency: Currency,
        ): Money {
            if (amount.length > 40 || !DECIMAL.matches(amount)) {
                throw InvalidMoneyException("Amount must be a decimal string such as 12.34.")
            }
            val decimal = BigDecimal(amount)
            if (decimal.scale() > currency.fractionDigits) {
                throw InvalidMoneyException("Amount has too many decimal places for ${currency.code}.")
            }
            val minor =
                try {
                    decimal
                        .setScale(currency.fractionDigits)
                        .movePointRight(currency.fractionDigits)
                        .longValueExact()
                } catch (_: ArithmeticException) {
                    throw InvalidMoneyException("Amount is too large.")
                }
            return Money(minor, currency)
        }
    }
}
