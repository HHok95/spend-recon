package com.hhok.spendrecon.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll

class MoneyPropertyTest :
    FunSpec({
        val currencies = listOf("JPY", "KRW", "AUD", "USD", "EUR", "BHD", "KWD").map(Currency::parse)
        val mixed =
            currencies.flatMap { left ->
                currencies.filter { it != left }.map { left to it }
            }

        test("a decimal string round-trips to the same minor units") {
            checkAll(Arb.element(currencies), Arb.long(0L..9_999_999L)) { currency, minor ->
                val money = Money(minor, currency)
                Money.parse(money.toDecimalString(), currency) shouldBe money
            }
        }

        test("adding two amounts of one currency adds the minor units") {
            checkAll(Arb.element(currencies), Arb.long(0L..1_000_000L), Arb.long(0L..1_000_000L)) { currency, left, right ->
                (Money(left, currency) + Money(right, currency)) shouldBe Money(left + right, currency)
            }
        }

        test("adding two amounts of different currencies throws") {
            checkAll(Arb.element(mixed), Arb.long(0L..1_000L), Arb.long(0L..1_000L)) { pair, left, right ->
                shouldThrow<CurrencyMismatch> {
                    Money(left, pair.first) + Money(right, pair.second)
                }
            }
        }
    })
