package com.hhok.spendrecon.application

import java.security.MessageDigest

/** SHA-256 hex of the raw request body. The idempotency row stores this, not the body. */
fun requestSha256(body: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(body).joinToString(separator = "") { byte ->
        "%02x".format(byte)
    }
