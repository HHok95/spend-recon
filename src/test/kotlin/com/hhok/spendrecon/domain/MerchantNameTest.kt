package com.hhok.spendrecon.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MerchantNameTest {
    @Test
    fun `normalization trims, lowercases, and collapses whitespace`() {
        assertThat(normalizeMerchant("  Cafe   Nero ")).isEqualTo("cafe nero")
    }

    @Test
    fun `a document number trims unicode whitespace and a blank is absent`() {
        assertThat(trimDocumentNumber("INV-100\t")).isEqualTo("INV-100")
        assertThat(trimDocumentNumber("\u00A0INV-100")).isEqualTo("INV-100")
        assertThat(trimDocumentNumber("   ")).isNull()
        assertThat(trimDocumentNumber("\u00A0 \t")).isNull()
        assertThat(normalizeDocumentNumber("\u00A0INV-100\t")).isEqualTo("inv-100")
        assertThat(normalizeDocumentNumber("   ")).isNull()
    }
}
