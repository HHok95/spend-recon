package com.hhok.spendrecon.application

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RequestHashTest {
    @Test
    fun `sha256 matches the standard test vector`() {
        assertThat(requestSha256("abc".toByteArray())).isEqualTo(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        )
    }
}
