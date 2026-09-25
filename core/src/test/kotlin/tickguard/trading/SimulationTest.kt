package tickguard.trading

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tickguard.stream.Decimal
import tickguard.testing.decimal
import java.time.LocalDate

/** Days from 2026-01-05, each bar opening and closing at the given prices. */
internal fun series(
    code: String,
    vararg openClose: Pair<String, String>,
    from: LocalDate = LocalDate.parse("2026-01-05"),
) = openClose.mapIndexed { i, (open, close) ->
    val o = decimal(open)
    val c = decimal(close)
    Bar(code, from.plusDays(i.toLong()), o, maxOf(o, c), minOf(o, c), c, decimal("1000"))
}

private val free = CostModel(commission = Decimal.ZERO, sellTax = Decimal.ZERO, slippage = Decimal.ZERO)

/** Wants all of [code] from the first close on. */
private class AllIn(
    private val code: String,
) : Strategy {
    override val name = "all-in"

    override fun targets(history: History) = mapOf(code to Decimal.ONE)
}

class SimulationTest {
    @Test
    fun `fills a decision at the next open, never at the close it was made on`() {
        val bars = mapOf("A" to series("A", "10" to "10", "12" to "15", "15" to "15"))

        val result = simulate(bars, AllIn("A"), free, decimal("100"))

        // Decided on day 1's close (10), filled at day 2's open (12): 8 shares, not 10.
        assertThat(result.fills.first().day).isEqualTo(LocalDate.parse("2026-01-06"))
        assertThat(result.fills.first().quantity).isEqualTo(8)
        assertThat(result.equity.map { it.equity.toPlainString() }).containsExactly("100", "124", "124")
    }

    @Test
    fun `lets a strategy see nothing past the day it decides on`() {
        val seen = mutableListOf<Pair<LocalDate, LocalDate?>>()
        val peeking =
            object : Strategy {
                override val name = "peeking"

                override fun targets(history: History): Map<String, Decimal> {
                    seen += history.day to history.bars("A", 1_000).lastOrNull()?.day
                    return emptyMap()
                }
            }

        simulate(mapOf("A" to series("A", "1" to "1", "2" to "2", "3" to "3")), peeking, free, decimal("1"))

        assertThat(seen).allSatisfy { (day, last) -> assertThat(last).isEqualTo(day) }
        assertThat(seen).hasSize(3)
    }

    @Test
    fun `charges slippage, commission and the sell tax, and never spends cash it does not have`() {
        val costs = CostModel(commission = decimal("0.01"), sellTax = decimal("0.02"), slippage = decimal("0.1"))
        val bars = mapOf("A" to series("A", "10" to "10", "10" to "10", "10" to "10"))
        val sellAll =
            object : Strategy {
                override val name = "in-then-out"
                private var day = 0

                override fun targets(history: History) = mapOf("A" to if (day++ == 0) Decimal.ONE else Decimal.ZERO)
            }

        val result = simulate(bars, sellAll, costs, decimal("100"))

        val (buy, sell) = result.fills
        // Buy at 11 (10 + 10% slippage), 1% commission: 100 / 11.11 = 9 shares, 99.99 spent.
        assertThat(buy.quantity).isEqualTo(9)
        assertThat(buy.price).isEqualTo(decimal("11"))
        assertThat(buy.fee).isEqualTo(decimal("0.99"))
        // Sell at 9, 1% commission and 2% tax on 81.
        assertThat(sell.price).isEqualTo(decimal("9"))
        assertThat(sell.fee).isEqualTo(decimal("2.43"))
        assertThat(result.equity.last().equity).isEqualTo(decimal("0.01") + decimal("81") - decimal("2.43"))
        assertThat(result.feesPaid).isEqualTo(decimal("3.42"))
    }

    @Test
    fun `holds a trade for a symbol whose market is closed until it opens`() {
        val a = series("A", "10" to "10", "10" to "10", "10" to "10")
        // B skips the second day: a holiday on its exchange.
        val b = series("B", "20" to "20", "20" to "20", "20" to "20").filterIndexed { i, _ -> i != 1 }
        val both =
            object : Strategy {
                override val name = "both"

                override fun targets(history: History) = mapOf("A" to decimal("0.5"), "B" to decimal("0.5"))
            }

        val result = simulate(mapOf("A" to a, "B" to b), both, free, decimal("100"))

        assertThat(result.fills.filter { it.code == "B" }.map { it.day }).containsExactly(LocalDate.parse("2026-01-07"))
    }

    @Test
    fun `scales weights above the whole portfolio down, and clamps each into range`() {
        assertThat(scaled(mapOf("A" to decimal("1"), "B" to decimal("1"))))
            .isEqualTo(mapOf("A" to decimal("0.5"), "B" to decimal("0.5")))
        assertThat(scaled(mapOf("A" to decimal("-1"), "B" to decimal("2"))))
            .isEqualTo(mapOf("A" to Decimal.ZERO, "B" to Decimal.ONE))
    }

    @Test
    fun `buys the benchmark once, in equal parts`() {
        val bars = mapOf("A" to series("A", "10" to "10", "10" to "20"), "B" to series("B", "10" to "10", "10" to "5"))

        val result = simulate(bars, BuyAndHold(), free, decimal("100"))

        assertThat(result.fills.map { it.code to it.quantity }).containsExactly("A" to 5L, "B" to 5L)
        assertThat(result.equity.last().equity).isEqualTo(decimal("125"))
    }
}
