package com.hhok.spendrecon.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MoneyTest {
    private val aud = Currency.parse("aud")
    private val jpy = Currency.parse("JPY")
    private val bhd = Currency.parse("BHD")

    @Test
    fun `currency codes are uppercase iso codes`() {
        assertThat(aud.code).isEqualTo("AUD")
        assertThat(aud.fractionDigits).isEqualTo(2)
        assertThat(jpy.fractionDigits).isEqualTo(0)
        assertThat(bhd.fractionDigits).isEqualTo(3)
    }

    @Test
    fun `aud keeps two minor digits and rejects a third`() {
        assertThat(Money.parse("12.34", aud)).isEqualTo(Money(1234, aud))
        assertThat(Money.parse("12.3", aud)).isEqualTo(Money(1230, aud))
        assertThat(Money.parse("12.30", aud).toDecimalString()).isEqualTo("12.30")
        assertThat(Money.parse("0.01", aud).minor).isEqualTo(1)
        assertThatThrownBy { Money.parse("12.340", aud) }
            .isInstanceOf(InvalidMoneyException::class.java)
            .hasMessageContaining("AUD")
    }

    @Test
    fun `jpy has no decimal places`() {
        assertThat(Money.parse("500", jpy)).isEqualTo(Money(500, jpy))
        assertThat(Money(500, jpy).toDecimalString()).isEqualTo("500")
        assertThatThrownBy { Money.parse("500.0", jpy) }
            .isInstanceOf(InvalidMoneyException::class.java)
    }

    @Test
    fun `bhd keeps three minor digits`() {
        assertThat(Money.parse("1.234", bhd)).isEqualTo(Money(1234, bhd))
        assertThat(Money(1234, bhd).toDecimalString()).isEqualTo("1.234")
    }

    @Test
    fun `amounts that are not decimal strings are rejected`() {
        assertThatThrownBy { Money.parse("12.34.1", aud) }.isInstanceOf(InvalidMoneyException::class.java)
        assertThatThrownBy { Money.parse("-1.00", aud) }.isInstanceOf(InvalidMoneyException::class.java)
        assertThatThrownBy { Money.parse("1e2", aud) }.isInstanceOf(InvalidMoneyException::class.java)
        assertThatThrownBy { Currency.parse("ZZZ") }.isInstanceOf(InvalidMoneyException::class.java)
    }

    @Test
    fun `same currency addition uses minor units`() {
        assertThat(Money.parse("1.50", aud) + Money.parse("2.25", aud)).isEqualTo(Money.parse("3.75", aud))
        assertThat(Money.parse("1", jpy) + Money.parse("2", jpy)).isEqualTo(Money(3, jpy))
    }

    @Test
    fun `mixed currencies cannot be added`() {
        assertThatThrownBy { Money(1, aud) + Money(1, jpy) }
            .isInstanceOf(CurrencyMismatch::class.java)
            .hasMessageContaining("AUD")
            .hasMessageContaining("JPY")
    }

    @Test
    fun `addition does not overflow a long`() {
        assertThatThrownBy { Money(Long.MAX_VALUE, jpy) + Money(1, jpy) }
            .isInstanceOf(ArithmeticException::class.java)
    }
}
