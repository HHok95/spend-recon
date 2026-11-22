package com.hhok.spendrecon.domain

import java.util.Locale

/**
 * Comparison form of a merchant name. Matching will score this string later.
 * Ingest only stores it: trim, lowercase, and collapse internal whitespace.
 */
fun normalizeMerchant(raw: String): String = raw.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

/**
 * Submitted receipt or invoice number with Unicode whitespace removed from both ends.
 * Blank is absent. [Char.isWhitespace] covers tab and NBSP.
 */
fun trimDocumentNumber(raw: String?): String? {
    if (raw == null) {
        return null
    }
    val trimmed = raw.trim()
    return trimmed.ifEmpty { null }
}

/**
 * The exact-key form of a receipt or invoice number: [trimDocumentNumber], then lowercase.
 * This is the only definition of that form. Ingest stores the result, and the unique index
 * does not trim or case-fold again.
 */
fun normalizeDocumentNumber(raw: String?): String? = trimDocumentNumber(raw)?.lowercase(Locale.ROOT)
