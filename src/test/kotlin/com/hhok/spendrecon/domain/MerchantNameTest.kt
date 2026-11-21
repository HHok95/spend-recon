package com.hhok.spendrecon.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MerchantNameTest {
    @Test
    fun `normalization trims, lowercases, and collapses whitespace`() {
        assertThat(normalizeMerchant("  Cafe   Nero ")).isEqualTo("cafe nero")
    }
}
