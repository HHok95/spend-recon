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
    R7_DUPLICATE,
}

enum class MatchStatus {
    MATCHED,
    SUGGESTED,
    EXCEPTION,
    APPROVED,
    REJECTED,
}

/**
 * POSSIBLE_DUPLICATE is a near-match. The status is EXCEPTION, so auto-approve
 * cannot select it. An exact receipt number is rejected at ingest and is not an exception type.
 */
enum class ExceptionType {
    CURRENCY_MISMATCH,
    AMOUNT_MISMATCH,
    NO_CANDIDATE,
    POSSIBLE_DUPLICATE,
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
 * A currency or amount miss holds its receipt only when the merchant names are at least this close.
 * The comparison is the R5 Jaro-Winkler value, from 0 to 1.
 */
fun merchantClearsExceptionHold(
    chargeNorm: String,
    receiptNorm: String,
    minimum: BigDecimal,
): Boolean = merchantSimilarity(chargeNorm, receiptNorm) >= minimum.toDouble()

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
 * receipt, and only when the merchant names clear [minMerchantScore]. Otherwise the
 * result is NO_CANDIDATE with no receipt. Several survivors that all fail the same
 * check, and clear the merchant bar, become an EXCEPTION with no receipt, so an
 * arbitrary receipt is not blocked. Passing receipts are scored.
 * A tie is SUGGESTED and the lowest document id is the one the row holds.
 * R7 then looks for a sibling with the same normalized merchant, amount, currency,
 * and date. That near-match is EXCEPTION POSSIBLE_DUPLICATE and still holds the
 * chosen receipt. A different date is left on the normal path.
 */
fun matchReceipts(
    charge: ChargeView,
    receipts: List<ReceiptView>,
    heldDocumentIds: Set<UUID>,
    minMerchantScore: BigDecimal = BigDecimal("0.60"),
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
        return currencyException(charge, survivors, minMerchantScore)
    }
    val sameAmount = sameCurrency.filter { amountsEqual(charge.amountMinor, it.amountMinor) }
    if (sameAmount.isEmpty()) {
        return amountException(charge, sameCurrency, minMerchantScore)
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
    return flagNearMatch(
        MatchDecision(
            status = status,
            candidate = winner.second,
            exceptionType = null,
            rulesFired = SCORING_RULES,
            tiedDocumentIds = if (tied.size > 1) tied.map { it.first.documentId } else emptyList(),
        ),
        receipts,
    )
}

private fun currencyException(
    charge: ChargeView,
    survivors: List<ReceiptView>,
    minMerchantScore: BigDecimal,
): MatchDecision {
    val eligible =
        survivors.filter {
            merchantClearsExceptionHold(charge.merchantNorm, it.merchantNorm, minMerchantScore)
        }
    if (eligible.isEmpty()) {
        return exception(ExceptionType.NO_CANDIDATE, candidate = null, rulesFired = emptyList())
    }
    if (eligible.size != 1) {
        return exception(ExceptionType.CURRENCY_MISMATCH, candidate = null, rulesFired = listOf(RuleId.R1_CURRENCY))
    }
    return exception(
        ExceptionType.CURRENCY_MISMATCH,
        candidate = scoreOf(charge, eligible.single()),
        rulesFired = listOf(RuleId.R1_CURRENCY, RuleId.R2_OWNER, RuleId.R4_DATE, RuleId.R5_MERCHANT, RuleId.R6_TAX),
    )
}

private fun amountException(
    charge: ChargeView,
    sameCurrency: List<ReceiptView>,
    minMerchantScore: BigDecimal,
): MatchDecision {
    val eligible =
        sameCurrency.filter {
            merchantClearsExceptionHold(charge.merchantNorm, it.merchantNorm, minMerchantScore)
        }
    if (eligible.isEmpty()) {
        return exception(ExceptionType.NO_CANDIDATE, candidate = null, rulesFired = emptyList())
    }
    if (eligible.size != 1) {
        return exception(
            ExceptionType.AMOUNT_MISMATCH,
            candidate = null,
            rulesFired = listOf(RuleId.R1_CURRENCY, RuleId.R2_OWNER, RuleId.R3_AMOUNT, RuleId.R4_DATE),
        )
    }
    return exception(
        ExceptionType.AMOUNT_MISMATCH,
        candidate = scoreOf(charge, eligible.single()),
        rulesFired = SCORING_RULES,
    )
}

/**
 * R7. A sibling receipt with the same merchant, amount, currency, and date is a
 * near-match (a similar document). Equality of those fields is the whole check.
 * The row stays on the receipt the scorer chose, with status EXCEPTION, so
 * auto-approve cannot take it. A sibling already held by a live match still counts.
 */
private fun flagNearMatch(
    decision: MatchDecision,
    receipts: List<ReceiptView>,
): MatchDecision {
    val chosen = decision.candidate ?: return decision
    val receipt = receipts.firstOrNull { it.documentId == chosen.documentId } ?: return decision
    val near =
        receipts.any { other ->
            other.documentId != receipt.documentId &&
                other.merchantNorm == receipt.merchantNorm &&
                other.amountMinor == receipt.amountMinor &&
                other.currency == receipt.currency &&
                other.docDate == receipt.docDate
        }
    if (!near) {
        return decision
    }
    return MatchDecision(
        status = MatchStatus.EXCEPTION,
        candidate = chosen,
        exceptionType = ExceptionType.POSSIBLE_DUPLICATE,
        rulesFired = decision.rulesFired + RuleId.R7_DUPLICATE,
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

private val SCORING_RULES =
    listOf(
        RuleId.R1_CURRENCY,
        RuleId.R2_OWNER,
        RuleId.R3_AMOUNT,
        RuleId.R4_DATE,
        RuleId.R5_MERCHANT,
        RuleId.R6_TAX,
    )

private val MERCHANT_WEIGHT = BigDecimal("0.7")

private val TAX_WEIGHT = BigDecimal("0.3")
