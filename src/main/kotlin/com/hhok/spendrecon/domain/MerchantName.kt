package com.hhok.spendrecon.domain

import java.util.Locale

/**
 * Comparison form of a merchant name. Matching will score this string later.
 * Ingest only stores it: trim, lowercase, and collapse internal whitespace.
 */
fun normalizeMerchant(raw: String): String = raw.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
