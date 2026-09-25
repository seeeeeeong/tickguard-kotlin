package tickguard.trading

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tickguard.stream.Decimal
import tickguard.testing.decimal
import java.time.LocalDate

class RiskTest {
    private val day = LocalDate.parse("2026-01-05")
    private val free = CostModel(Decimal.ZERO, Decimal.ZERO, Decimal.ZERO)

    private fun reasons(risk: RiskEngine) = risk.notes().map { it.reason }

    @Test
    fun `drops what is not on the allowlist and caps each weight`() {
        val risk = RiskEngine(RiskLimits(maxWeight = decimal("0.2"), allowed = setOf("A", "B")))

        val reviewed =
            risk.review(
                day,
                mapOf("A" to decimal("0.5"), "B" to decimal("0.1"), "X" to decimal("0.3")),
                held = emptySet(),
            )

        assertThat(reviewed).isEqualTo(mapOf("A" to decimal("0.2"), "B" to decimal("0.1")))
        assertThat(reasons(risk)).containsExactly("weight 50.0% capped at 20.0%", "not on the allowlist")
    }

    @Test
    fun `lets no new position past the limit, but counts a sale as room`() {
        val risk = RiskEngine(RiskLimits(maxPositions = 2))

        // Holding A and B; selling B out makes room for C, not for D as well.
        val reviewed =
            risk.review(
                day,
                mapOf("B" to Decimal.ZERO, "C" to decimal("0.3"), "D" to decimal("0.3")),
                held = setOf("A", "B"),
            )

        assertThat(reviewed.keys).containsExactly("B", "C")
        assertThat(reasons(risk)).containsExactly("already at 2 positions")
    }

    @Test
    fun `stops buying for a day after a close that lost too much, then trades again`() {
        val risk = RiskEngine(RiskLimits(dailyLoss = decimal("0.05")))

        risk.observe(day, decimal("100"))
        risk.observe(day.plusDays(1), decimal("94"))
        assertThat(risk.state).isEqualTo(TradingState.REDUCING)
        assertThat(risk.allowsBuys()).isFalse()

        risk.observe(day.plusDays(2), decimal("94"))
        assertThat(risk.state).isEqualTo(TradingState.ACTIVE)
    }

    @Test
    fun `halts for good past the drawdown limit, whatever the strategy wants`() {
        val risk = RiskEngine(RiskLimits(maxDrawdown = decimal("0.2")))

        risk.observe(day, decimal("100"))
        risk.observe(day.plusDays(1), decimal("130"))
        risk.observe(day.plusDays(2), decimal("103"))
        risk.observe(day.plusDays(3), decimal("200"))

        assertThat(risk.state).isEqualTo(TradingState.HALTED)
        assertThat(risk.review(day, mapOf("A" to Decimal.ONE), emptySet())).isEmpty()
        assertThat(reasons(risk)).first().asString().startsWith("drawdown past 20.0%")
    }

    @Test
    fun `cuts a buy to the order value limit`() {
        val risk = RiskEngine(RiskLimits(maxOrderValue = decimal("1000")))

        assertThat(risk.capBuy(day, "A", 50, decimal("30"))).isEqualTo(33)
        assertThat(risk.capBuy(day, "A", 10, decimal("30"))).isEqualTo(10)
    }

    @Test
    fun `holds a simulated strategy to the limits it runs under`() {
        // Falls 10% on day 2: day 3 must not buy B, which the strategy wants from day 2 on.
        val a = series("A", "100" to "100", "100" to "90", "90" to "90", "90" to "90")
        val b = series("B", "10" to "10", "10" to "10", "10" to "10", "10" to "10")
        var day = 0
        val strategy =
            object : Strategy {
                override val name = "a-then-b"

                override fun targets(history: History): Map<String, Decimal> =
                    if (day++ == 0) mapOf("A" to Decimal.ONE) else mapOf("A" to decimal("0.5"), "B" to decimal("0.5"))
            }

        val result =
            simulate(
                mapOf("A" to a, "B" to b),
                strategy,
                free,
                decimal("1000"),
                RiskEngine(RiskLimits(dailyLoss = decimal("0.05"), maxOrderValue = decimal("600"))),
            )

        val byDay = result.fills.groupBy { it.day }
        // Day 3 (the 7th) sells A down to half, as reducing allows, and buys nothing.
        assertThat(byDay.getValue(LocalDate.parse("2026-01-07")).map { it.side }).containsOnly(Side.SELL)
        assertThat(result.fills.filter { it.code == "B" }.map { it.day }).containsExactly(LocalDate.parse("2026-01-08"))
        // The first buy of A (10 shares, 1000) was cut to 6 by the 600 order limit.
        assertThat(result.fills.first().quantity).isEqualTo(6)
        assertThat(result.riskNotes.map { it.reason }).anyMatch { it.startsWith("lost more than 5.0%") }
    }
}
