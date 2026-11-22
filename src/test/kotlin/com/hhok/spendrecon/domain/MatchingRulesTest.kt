package com.hhok.spendrecon.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class MatchingRulesTest {
    @Test
    fun `R1_CURRENCY holds the receipt when the only candidate currency differs`() {
        val receipt = receipt(currency = "USD")
        val decision = matchReceipts(charge(currency = "AUD"), listOf(receipt), emptySet())

        assertThat(decision.status).isEqualTo(MatchStatus.EXCEPTION)
        assertThat(decision.exceptionType).isEqualTo(ExceptionType.CURRENCY_MISMATCH)
        assertThat(decision.candidate?.documentId).isEqualTo(receipt.documentId)
        assertThat(decision.rulesFired).contains(RuleId.R1_CURRENCY)
        assertThat(shouldAutoApprove(decision.status, decision.candidate?.score, BigDecimal("0.95"))).isFalse()
    }

    @Test
    fun `R2_OWNER drops a receipt from another cardholder`() {
        val receipt = receipt(owner = "someone-else", currency = "USD")
        val decision = matchReceipts(charge(owner = "owner-1", currency = "AUD"), listOf(receipt), emptySet())

        assertThat(decision.status).isEqualTo(MatchStatus.EXCEPTION)
        assertThat(decision.exceptionType).isEqualTo(ExceptionType.NO_CANDIDATE)
        assertThat(decision.candidate).isNull()
        assertThat(ownersEqual("owner-1", "owner-1")).isTrue()
        assertThat(ownersEqual("owner-1", "someone-else")).isFalse()
    }

    @Test
    fun `R3_AMOUNT holds the receipt when the only candidate amount differs`() {
        val receipt = receipt(amount = 5500)
        val decision = matchReceipts(charge(amount = 5000), listOf(receipt), emptySet())

        assertThat(decision.status).isEqualTo(MatchStatus.EXCEPTION)
        assertThat(decision.exceptionType).isEqualTo(ExceptionType.AMOUNT_MISMATCH)
        assertThat(decision.candidate?.documentId).isEqualTo(receipt.documentId)
        assertThat(decision.candidate?.score).isEqualByComparingTo("1.000")
        assertThat(decision.rulesFired).contains(RuleId.R3_AMOUNT)
        assertThat(amountsEqual(5000, 5000)).isTrue()
        assertThat(amountsEqual(5000, 5500)).isFalse()
    }

    @Test
    fun `an amount mismatch holds a similar merchant and releases a different one`() {
        val similar = receipt(merchant = "marhta", amount = 5500)
        val held =
            matchReceipts(
                charge(merchant = "martha", amount = 5000),
                listOf(similar),
                emptySet(),
            )
        assertThat(merchantSimilarity("martha", "marhta")).isGreaterThan(0.60)
        assertThat(held.exceptionType).isEqualTo(ExceptionType.AMOUNT_MISMATCH)
        assertThat(held.candidate?.documentId).isEqualTo(similar.documentId)

        val distant = receipt(merchant = "zzzz", amount = 5500)
        val released =
            matchReceipts(
                charge(merchant = "acme", amount = 5000),
                listOf(distant),
                emptySet(),
            )
        assertThat(merchantSimilarity("acme", "zzzz")).isLessThan(0.60)
        assertThat(released.exceptionType).isEqualTo(ExceptionType.NO_CANDIDATE)
        assertThat(released.candidate).isNull()
        assertThat(released.rulesFired).isEmpty()

        val later = matchReceipts(charge(merchant = "zzzz", amount = 5500), listOf(distant), emptySet())
        assertThat(later.candidate?.documentId).isEqualTo(distant.documentId)
        assertThat(later.status).isEqualTo(MatchStatus.MATCHED)
    }

    @Test
    fun `an amount mismatch holds the receipt at merchant score 0_60`() {
        assertThat(merchantSimilarity("abcde", "xabyz")).isEqualTo(0.6)
        val receipt = receipt(merchant = "xabyz", amount = 5500)
        val charge = charge(merchant = "abcde", amount = 5000)

        val atBar = matchReceipts(charge, listOf(receipt), emptySet(), BigDecimal("0.60"))
        assertThat(merchantClearsExceptionHold("abcde", "xabyz", BigDecimal("0.60"))).isTrue()
        assertThat(atBar.exceptionType).isEqualTo(ExceptionType.AMOUNT_MISMATCH)
        assertThat(atBar.candidate?.documentId).isEqualTo(receipt.documentId)

        val under = matchReceipts(charge, listOf(receipt), emptySet(), BigDecimal("0.61"))
        assertThat(merchantClearsExceptionHold("abcde", "xabyz", BigDecimal("0.61"))).isFalse()
        assertThat(under.exceptionType).isEqualTo(ExceptionType.NO_CANDIDATE)
        assertThat(under.candidate).isNull()
    }

    @Test
    fun `R4_DATE keeps a receipt three days away and drops one four days away`() {
        val inside = receipt(date = DAY.plusDays(3), merchant = "other")
        val kept = matchReceipts(charge(), listOf(inside), emptySet())
        assertThat(kept.exceptionType).isNotEqualTo(ExceptionType.NO_CANDIDATE)
        assertThat(kept.candidate?.documentId).isEqualTo(inside.documentId)
        assertThat(datesWithinWindow(DAY, DAY.plusDays(3))).isTrue()

        val outside = receipt(date = DAY.plusDays(4))
        val dropped = matchReceipts(charge(), listOf(outside), emptySet())
        assertThat(dropped.exceptionType).isEqualTo(ExceptionType.NO_CANDIDATE)
        assertThat(dropped.candidate).isNull()
        assertThat(datesWithinWindow(DAY, DAY.plusDays(4))).isFalse()
        assertThat(decisionRules(dropped)).doesNotContain(RuleId.R4_DATE)
    }

    @Test
    fun `R5_MERCHANT scores jaro winkler at weight 0_7`() {
        assertThat(jaroWinkler("martha", "marhta")).isCloseTo(0.961, within(0.001))
        assertThat(jaroWinkler("dwayne", "duane")).isCloseTo(0.840, within(0.001))
        assertThat(jaroWinkler("cafe", "cafe")).isEqualTo(1.0)

        val identical = matchReceipts(charge(merchant = "cafe", tax = null), listOf(receipt(merchant = "cafe", tax = null)), emptySet())
        assertThat(identical.candidate?.score).isEqualByComparingTo("0.700")
        assertThat(identical.rulesFired).contains(RuleId.R5_MERCHANT)
        assertThat(identical.status).isEqualTo(MatchStatus.SUGGESTED)

        val partial = matchReceipts(charge(merchant = "martha", tax = null), listOf(receipt(merchant = "marhta", tax = null)), emptySet())
        val expected =
            BigDecimal
                .valueOf(jaroWinkler("martha", "marhta"))
                .multiply(BigDecimal("0.7"))
                .setScale(3, java.math.RoundingMode.HALF_UP)
        assertThat(partial.candidate?.score).isEqualByComparingTo(expected)
        assertThat(partial.candidate?.score).isLessThan(BigDecimal("0.700"))
    }

    @Test
    fun `R6_TAX adds 0_3 only when tax is present on both and equal`() {
        assertThat(taxComponent(500, 500)).isEqualByComparingTo(BigDecimal.ONE)
        assertThat(taxComponent(0, 0)).isEqualByComparingTo(BigDecimal.ONE)
        assertThat(taxComponent(null, null)).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(taxComponent(500, null)).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(taxComponent(500, 400)).isEqualByComparingTo(BigDecimal.ZERO)

        val equal = matchReceipts(charge(tax = 500), listOf(receipt(tax = 500)), emptySet())
        assertThat(equal.candidate?.score).isEqualByComparingTo("1.000")
        assertThat(equal.status).isEqualTo(MatchStatus.MATCHED)
        assertThat(equal.rulesFired).contains(RuleId.R6_TAX)

        val missing = matchReceipts(charge(tax = null), listOf(receipt(tax = null)), emptySet())
        assertThat(missing.candidate?.score).isEqualByComparingTo("0.700")

        val zero = matchReceipts(charge(tax = 0), listOf(receipt(tax = 0)), emptySet())
        assertThat(zero.candidate?.score).isEqualByComparingTo("1.000")
    }

    @Test
    fun `a tie is suggested and both receipts are listed`() {
        // One day apart, so the scores tie and the receipts are not a near-match.
        val first = receipt(id = UUID.fromString("00000000-0000-0000-0000-000000000002"), date = DAY.plusDays(1))
        val second = receipt(id = UUID.fromString("00000000-0000-0000-0000-000000000001"))
        val decision = matchReceipts(charge(), listOf(first, second), emptySet())

        assertThat(decision.status).isEqualTo(MatchStatus.SUGGESTED)
        assertThat(decision.candidate?.documentId).isEqualTo(second.documentId)
        assertThat(decision.candidate?.score).isEqualByComparingTo("1.000")
        assertThat(decision.tiedDocumentIds).containsExactly(second.documentId, first.documentId)
        assertThat(shouldAutoApprove(decision.status, decision.candidate?.score, BigDecimal("0.00"))).isFalse()
    }

    @Test
    fun `a receipt held by a live match is skipped`() {
        val receipt = receipt()
        val decision = matchReceipts(charge(), listOf(receipt), setOf(receipt.documentId))

        assertThat(decision.exceptionType).isEqualTo(ExceptionType.NO_CANDIDATE)
        assertThat(decision.candidate).isNull()
    }

    @Test
    fun `an equal amount wins over a mismatched sibling`() {
        val good = receipt(amount = 5000)
        val wrong = receipt(amount = 5500)
        val decision = matchReceipts(charge(amount = 5000), listOf(wrong, good), emptySet())

        assertThat(decision.status).isEqualTo(MatchStatus.MATCHED)
        assertThat(decision.candidate?.documentId).isEqualTo(good.documentId)
        assertThat(decision.exceptionType).isNull()
    }

    @Test
    fun `no receipts is a null score exception`() {
        val decision = matchReceipts(charge(), emptyList(), emptySet())
        assertThat(decision.exceptionType).isEqualTo(ExceptionType.NO_CANDIDATE)
        assertThat(decision.candidate).isNull()
        assertThat(decision.rulesFired).isEmpty()
    }

    @Test
    fun `auto approve is at the threshold, just under it, and off above 1`() {
        val matched = BigDecimal("1.000")
        assertThat(shouldAutoApprove(MatchStatus.MATCHED, matched, BigDecimal("1.000"))).isTrue()
        assertThat(shouldAutoApprove(MatchStatus.MATCHED, BigDecimal("0.999"), BigDecimal("1.000"))).isFalse()
        assertThat(shouldAutoApprove(MatchStatus.MATCHED, matched, BigDecimal("1.001"))).isFalse()
        assertThat(shouldAutoApprove(MatchStatus.SUGGESTED, BigDecimal("0.700"), BigDecimal("0.000"))).isFalse()
        assertThat(shouldAutoApprove(MatchStatus.EXCEPTION, matched, BigDecimal("0.000"))).isFalse()
        assertThat(shouldAutoApprove(MatchStatus.MATCHED, null, BigDecimal("0.000"))).isFalse()
    }

    @Test
    fun `R7_DUPLICATE flags the same merchant amount and date and leaves a different date alone`() {
        val first = receipt()
        val second = receipt()
        val near = matchReceipts(charge(), listOf(first, second), emptySet())
        assertThat(near.status).isEqualTo(MatchStatus.EXCEPTION)
        assertThat(near.exceptionType).isEqualTo(ExceptionType.POSSIBLE_DUPLICATE)
        assertThat(near.candidate?.documentId).isIn(first.documentId, second.documentId)
        assertThat(near.rulesFired).contains(RuleId.R7_DUPLICATE)
        assertThat(shouldAutoApprove(near.status, near.candidate?.score, BigDecimal.ZERO)).isFalse()

        val held = matchReceipts(charge(), listOf(first, second), setOf(first.documentId))
        assertThat(held.status).isEqualTo(MatchStatus.EXCEPTION)
        assertThat(held.exceptionType).isEqualTo(ExceptionType.POSSIBLE_DUPLICATE)
        assertThat(held.candidate?.documentId).isEqualTo(second.documentId)
        assertThat(shouldAutoApprove(held.status, held.candidate?.score, BigDecimal.ZERO)).isFalse()

        val otherCurrency = receipt(currency = "USD")
        val sameMoney = matchReceipts(charge(), listOf(first, otherCurrency), emptySet())
        assertThat(sameMoney.exceptionType).isNull()
        assertThat(sameMoney.candidate?.documentId).isEqualTo(first.documentId)
        assertThat(sameMoney.rulesFired).doesNotContain(RuleId.R7_DUPLICATE)

        val nextDay = receipt(date = DAY.plusDays(1))
        val insideWindow = matchReceipts(charge(date = DAY.plusDays(1)), listOf(first, nextDay), emptySet())
        assertThat(insideWindow.exceptionType).isNull()
        assertThat(insideWindow.rulesFired).doesNotContain(RuleId.R7_DUPLICATE)

        val later = receipt(date = DAY.plusDays(10))
        val repeat = matchReceipts(charge(date = DAY.plusDays(10)), listOf(first, later), emptySet())
        assertThat(repeat.status).isEqualTo(MatchStatus.MATCHED)
        assertThat(repeat.exceptionType).isNull()
        assertThat(repeat.candidate?.documentId).isEqualTo(later.documentId)
        assertThat(repeat.rulesFired).doesNotContain(RuleId.R7_DUPLICATE)
        assertThat(shouldAutoApprove(repeat.status, repeat.candidate?.score, BigDecimal("0.95"))).isTrue()
    }

    @Test
    fun `dwayne and duane score as matched and under the default threshold`() {
        val decision =
            matchReceipts(
                charge(merchant = "dwayne", tax = 100),
                listOf(receipt(merchant = "duane", tax = 100)),
                emptySet(),
            )
        val score = decision.candidate!!.score
        assertThat(score).isGreaterThanOrEqualTo(BigDecimal("0.850"))
        assertThat(score).isLessThan(BigDecimal("0.950"))
        assertThat(decision.status).isEqualTo(MatchStatus.MATCHED)
        assertThat(shouldAutoApprove(decision.status, score, BigDecimal("0.95"))).isFalse()
        assertThat(shouldAutoApprove(decision.status, score, score)).isTrue()
    }

    private fun decisionRules(decision: MatchDecision): List<RuleId> = decision.rulesFired

    private fun charge(
        owner: String = "owner-1",
        merchant: String = "cafe",
        amount: Long = 5000,
        currency: String = "AUD",
        tax: Long? = 500,
        date: LocalDate = DAY,
    ) = ChargeView(owner, merchant, amount, currency, tax, date)

    private fun receipt(
        id: UUID = UUID.randomUUID(),
        owner: String = "owner-1",
        merchant: String = "cafe",
        amount: Long = 5000,
        currency: String = "AUD",
        tax: Long? = 500,
        date: LocalDate = DAY,
    ) = ReceiptView(id, owner, merchant, amount, currency, tax, date)

    private companion object {
        val DAY: LocalDate = LocalDate.parse("2026-11-21")
    }
}
