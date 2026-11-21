package com.hhok.spendrecon.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Identifiers from the architecture, in the order the rules are listed. */
enum class RuleId {
    R1_CURRENCY,
    R2_OWNER,
    R3_AMOUNT,
    R4_DATE,
    R5_MERCHANT,
    R6_TAX,
}

enum class MatchStatus {
    MATCHED,
    SUGGESTED,
    EXCEPTION,
    APPROVED,
    REJECTED,
}

/** R7's DUPLICATE_DOCUMENT stays out until the duplicate check is decided. */
enum class ExceptionType {
    CURRENCY_MISMATCH,
    AMOUNT_MISMATCH,
    NO_CANDIDATE,
}

/**
 * A receipt and the score that belongs to it.
 * The database check is the same rule: a score is present exactly when a receipt is.
 */
data class Candidate(
    val documentId: UUID,
    val score: BigDecimal,
) {
    init {
        require(score.scale() == 3) { "Score is stored at 3 decimal places." }
        require(score >= BigDecimal.ZERO && score <= BigDecimal.ONE) { "Score must be from 0 to 1." }
    }
}

data class ChargeView(
    val ownerId: String,
    val merchantNorm: String,
    val amountMinor: Long,
    val currency: String,
    val taxMinor: Long?,
    val txnDate: LocalDate,
)

data class ReceiptView(
    val documentId: UUID,
    val ownerId: String,
    val merchantNorm: String,
    val amountMinor: Long,
    val currency: String,
    val taxMinor: Long?,
    val docDate: LocalDate,
)

/**
 * The engine result, before auto-approve.
 * [candidate] is null when the match holds no receipt, and then [status] is EXCEPTION.
 */
data class MatchDecision(
    val status: MatchStatus,
    val candidate: Candidate?,
    val exceptionType: ExceptionType?,
    val rulesFired: List<RuleId>,
    val tiedDocumentIds: List<UUID> = emptyList(),
) {
    init {
        if (candidate == null) {
            require(status == MatchStatus.EXCEPTION) { "A match with no receipt is an exception." }
        }
        if (status == MatchStatus.EXCEPTION) {
            requireNotNull(exceptionType) { "An exception names its type." }
        } else {
            require(exceptionType == null) { "Only an exception has an exception type." }
            requireNotNull(candidate) { "A matched or suggested result holds a receipt." }
        }
    }
}

object RuleSet {
    const val VERSION = "r1-r6.v1"
    val MATCHED_AT: BigDecimal = BigDecimal("0.850")
    const val DATE_WINDOW_DAYS = 3L
}

/** R1. Currencies must be equal. */
fun currenciesEqual(
    chargeCurrency: String,
    receiptCurrency: String,
): Boolean = chargeCurrency == receiptCurrency

/** R2. Hard filter: same card owner. */
fun ownersEqual(
    chargeOwner: String,
    receiptOwner: String,
): Boolean = chargeOwner == receiptOwner

/** R3. Amounts in minor units must be equal. */
fun amountsEqual(
    chargeMinor: Long,
    receiptMinor: Long,
): Boolean = chargeMinor == receiptMinor

/** R4. Hard filter: the dates are at most three days apart, inclusive. */
fun datesWithinWindow(
    txnDate: LocalDate,
    docDate: LocalDate,
    windowDays: Long = RuleSet.DATE_WINDOW_DAYS,
): Boolean = kotlin.math.abs(ChronoUnit.DAYS.between(txnDate, docDate)) <= windowDays

/** R5. Jaro-Winkler on the normalized merchant names. */
fun merchantSimilarity(
    chargeNorm: String,
    receiptNorm: String,
): Double = jaroWinkler(chargeNorm, receiptNorm)

/**
 * R6. 1 when tax is present on both sides and equal, otherwise 0.
 * A null tax is absent. Zero is present.
 */
fun taxComponent(
    chargeTax: Long?,
    receiptTax: Long?,
): BigDecimal =
    if (chargeTax != null && receiptTax != null && chargeTax == receiptTax) {
        BigDecimal.ONE
    } else {
        BigDecimal.ZERO
    }

/** Score = 0.7 * merchant + 0.3 * tax, rounded half up to the numeric(4,3) column. */
fun matchScore(
    merchant: Double,
    tax: BigDecimal,
): BigDecimal {
    val scaled =
        BigDecimal
            .valueOf(merchant)
            .multiply(MERCHANT_WEIGHT)
            .add(tax.multiply(TAX_WEIGHT))
            .setScale(3, RoundingMode.HALF_UP)
    return when {
        scaled > BigDecimal.ONE -> BigDecimal("1.000")
        scaled < BigDecimal.ZERO -> BigDecimal("0.000")
        else -> scaled
    }
}

/**
 * Auto-approve only a MATCHED score at or above the threshold.
 * A threshold above 1 turns it off. SUGGESTED, EXCEPTION, and a null score never pass.
 */
fun shouldAutoApprove(
    status: MatchStatus,
    score: BigDecimal?,
    threshold: BigDecimal,
): Boolean {
    if (threshold > BigDecimal.ONE) {
        return false
    }
    if (status != MatchStatus.MATCHED || score == null) {
        return false
    }
    return score >= threshold
}

/**
 * Picks one result for a charge.
 *
 * Owner (R2), date (R4), and a receipt already held by a live match are hard filters:
 * the receipt is skipped and is not pinned. Currency (R1) and amount (R3) run on what
 * remains. One survivor that fails either check becomes an EXCEPTION that holds that
 * receipt. Several survivors that all fail the same check become an EXCEPTION with no
 * receipt, so an arbitrary receipt is not blocked. Passing receipts are scored.
 * A tie is SUGGESTED and the lowest document id is the one the row holds.
 */
fun matchReceipts(
    charge: ChargeView,
    receipts: List<ReceiptView>,
    heldDocumentIds: Set<UUID>,
): MatchDecision {
    val survivors =
        receipts.filter { receipt ->
            receipt.documentId !in heldDocumentIds &&
                ownersEqual(charge.ownerId, receipt.ownerId) &&
                datesWithinWindow(charge.txnDate, receipt.docDate)
        }
    if (survivors.isEmpty()) {
        return exception(ExceptionType.NO_CANDIDATE, candidate = null, rulesFired = emptyList())
    }
    val sameCurrency = survivors.filter { currenciesEqual(charge.currency, it.currency) }
    if (sameCurrency.isEmpty()) {
        return currencyException(charge, survivors)
    }
    val sameAmount = sameCurrency.filter { amountsEqual(charge.amountMinor, it.amountMinor) }
    if (sameAmount.isEmpty()) {
        return amountException(charge, sameCurrency)
    }
    val scored = sameAmount.map { receipt -> receipt to scoreOf(charge, receipt) }
    val best = scored.maxOf { it.second.score }
    val tied = scored.filter { it.second.score.compareTo(best) == 0 }.sortedBy { it.first.documentId }
    val winner = tied.first()
    val status =
        if (tied.size > 1) {
            MatchStatus.SUGGESTED
        } else if (winner.second.score >= RuleSet.MATCHED_AT) {
            MatchStatus.MATCHED
        } else {
            MatchStatus.SUGGESTED
        }
    return MatchDecision(
        status = status,
        candidate = winner.second,
        exceptionType = null,
        rulesFired = ALL_RULES,
        tiedDocumentIds = if (tied.size > 1) tied.map { it.first.documentId } else emptyList(),
    )
}

private fun currencyException(
    charge: ChargeView,
    survivors: List<ReceiptView>,
): MatchDecision {
    if (survivors.size != 1) {
        return exception(ExceptionType.CURRENCY_MISMATCH, candidate = null, rulesFired = listOf(RuleId.R1_CURRENCY))
    }
    return exception(
        ExceptionType.CURRENCY_MISMATCH,
        candidate = scoreOf(charge, survivors.single()),
        rulesFired = listOf(RuleId.R1_CURRENCY, RuleId.R2_OWNER, RuleId.R4_DATE, RuleId.R5_MERCHANT, RuleId.R6_TAX),
    )
}

private fun amountException(
    charge: ChargeView,
    sameCurrency: List<ReceiptView>,
): MatchDecision {
    if (sameCurrency.size != 1) {
        return exception(
            ExceptionType.AMOUNT_MISMATCH,
            candidate = null,
            rulesFired = listOf(RuleId.R1_CURRENCY, RuleId.R2_OWNER, RuleId.R3_AMOUNT, RuleId.R4_DATE),
        )
    }
    return exception(
        ExceptionType.AMOUNT_MISMATCH,
        candidate = scoreOf(charge, sameCurrency.single()),
        rulesFired = ALL_RULES,
    )
}

private fun exception(
    type: ExceptionType,
    candidate: Candidate?,
    rulesFired: List<RuleId>,
): MatchDecision =
    MatchDecision(
        status = MatchStatus.EXCEPTION,
        candidate = candidate,
        exceptionType = type,
        rulesFired = rulesFired,
    )

private fun scoreOf(
    charge: ChargeView,
    receipt: ReceiptView,
): Candidate =
    Candidate(
        documentId = receipt.documentId,
        score =
            matchScore(
                merchantSimilarity(charge.merchantNorm, receipt.merchantNorm),
                taxComponent(charge.taxMinor, receipt.taxMinor),
            ),
    )

private val ALL_RULES = RuleId.entries

private val MERCHANT_WEIGHT = BigDecimal("0.7")

private val TAX_WEIGHT = BigDecimal("0.3")
