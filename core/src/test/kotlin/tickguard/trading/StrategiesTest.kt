package tickguard.trading

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tickguard.stream.Decimal
import tickguard.testing.decimal
import java.time.LocalDate

class StrategiesTest {
    /** Closes only, one bar a day; open equals close. */
    private fun closes(
        code: String,
        vararg prices: Int,
    ) = series(code, *prices.map { "$it" to "$it" }.toTypedArray())

    private fun historyOn(
        bars: Map<String, List<Bar>>,
        index: Int,
    ) = History(
        LocalDate.parse("2026-01-05").plusDays(index.toLong()),
        bars,
        bars.mapValues {
            minOf(index + 1, it.value.size)
        },
    )

    @Test
    fun `trend filter holds what is above its average and leaves the rest in cash`() {
        val bars = mapOf("UP" to closes("UP", 10, 11, 12, 13), "DOWN" to closes("DOWN", 13, 12, 11, 10))

        val targets = TrendFilter(days = 3).targets(historyOn(bars, 3))

        assertThat(targets).isEqualTo(mapOf("UP" to Decimal.ONE, "DOWN" to Decimal.ZERO))
    }

    @Test
    fun `trend filter leaves alone a symbol it cannot judge yet`() {
        val bars = mapOf("OLD" to closes("OLD", 10, 11, 12), "NEW" to closes("NEW", 5, 6))

        val targets =
            TrendFilter(days = 3).targets(
                History(
                    LocalDate.parse("2026-01-07"),
                    bars,
                    mapOf(
                        "OLD" to 3,
                        "NEW" to 2,
                    ),
                ),
            )

        assertThat(targets.keys).containsExactly("OLD")
    }

    @Test
    fun `momentum holds the leaders, and nothing that fell`() {
        val bars =
            mapOf(
                "FAST" to closes("FAST", 10, 12, 15),
                "SLOW" to closes("SLOW", 10, 10, 11),
                "FALL" to closes("FALL", 10, 9, 8),
            )

        val targets = MomentumRotation(lookback = 2, top = 2, every = 1).targets(historyOn(bars, 2))

        assertThat(targets).isEqualTo(mapOf("FAST" to decimal("0.5"), "SLOW" to decimal("0.5"), "FALL" to Decimal.ZERO))
    }

    @Test
    fun `momentum goes to cash when everything fell`() {
        val bars = mapOf("A" to closes("A", 10, 9, 8), "B" to closes("B", 10, 9, 9))

        val targets = MomentumRotation(lookback = 2, top = 1, every = 1).targets(historyOn(bars, 2))

        assertThat(targets.values).allMatch { it == Decimal.ZERO }
    }

    @Test
    fun `momentum trades only on its rebalance days`() {
        val bars = mapOf("A" to closes("A", 10, 11, 12, 13, 14, 15))
        val strategy = MomentumRotation(lookback = 1, top = 1, every = 3)

        val decided = (1..5).map { strategy.targets(historyOn(bars, it)).isNotEmpty() }

        assertThat(decided).containsExactly(true, false, false, true, false)
    }

    @Test
    fun `trend filter steps aside in a long decline that holding rides all the way down`() {
        // Up for 30 days, then down 60% over 30 days.
        val prices = (0 until 30).map { 100 + it } + (0 until 30).map { 129 - it * 26 / 10 }
        val bars = mapOf("A" to closes("A", *prices.toIntArray()))
        val free = CostModel(Decimal.ZERO, Decimal.ZERO, Decimal.ZERO)

        val trend = simulate(bars, TrendFilter(days = 10), free, decimal("10000")).performance
        val hold = simulate(bars, BuyAndHold(), free, decimal("10000")).performance

        assertThat(trend.maxDrawdown).isLessThan(hold.maxDrawdown / 2)
    }
}
