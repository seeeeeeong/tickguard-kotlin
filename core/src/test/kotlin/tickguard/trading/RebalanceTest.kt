package tickguard.trading

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tickguard.stream.Decimal
import tickguard.testing.decimal
import java.time.LocalDate

class RebalanceTest {
    private fun flat(
        code: String,
        price: String,
    ) = series(code, *Array(3) { price to price })

    private fun sleeve(
        lossAction: LossAction = LossAction.STOP_BUYING,
        capital: String = "100",
    ) = Sleeve("A", "A", decimal(capital), listOf("X", "Y"), ::BuyAndHold, decimal("0.05"), decimal("0.25"), lossAction)

    @Test
    fun `rebalances on what the sleeve is worth now, not what it started with`() {
        // Started with 100; X doubled, so the sleeve is worth 150 and X is two thirds of it.
        val position = SleevePosition(mapOf("X" to decimal("1"), "Y" to decimal("1")), decimal("0"), decimal("0"), 2)
        val bars = mapOf("X" to flat("X", "100"), "Y" to flat("Y", "50"))

        val proposal = proposeRebalance(sleeve(), position, bars)!!

        assertThat(proposal.value).isEqualTo(decimal("150"))
        assertThat(proposal.trades.map { it.code to it.side }).containsExactly("X" to Side.SELL, "Y" to Side.BUY)
        // 100/150 divides to twenty places; the order goes out in cents.
        assertThat(
            proposal.trades
                .first()
                .value
                .format(2),
        ).isEqualTo("25.00")
        assertThat(proposal.stopped).isNull()
    }

    @Test
    fun `only sells past the loss limit when the sleeve stops buying, and trades nothing when it stops for good`() {
        // Worth 70 against 100: past a 25% limit.
        val position = SleevePosition(mapOf("X" to decimal("1")), decimal("10"), decimal("0"), 1)
        val bars = mapOf("X" to flat("X", "60"), "Y" to flat("Y", "10"))

        val reducing = proposeRebalance(sleeve(LossAction.STOP_BUYING), position, bars)!!
        val finished = proposeRebalance(sleeve(LossAction.STOP_SLEEVE), position, bars)!!

        assertThat(reducing.stopped).isEqualTo(LossAction.STOP_BUYING)
        assertThat(reducing.trades.map { it.side }).containsOnly(Side.SELL).isNotEmpty()
        assertThat(finished.stopped).isEqualTo(LossAction.STOP_SLEEVE)
        assertThat(finished.trades).isEmpty()
    }

    @Test
    fun `proposes nothing without bars to price it`() {
        assertThat(
            proposeRebalance(sleeve(), SleevePosition(emptyMap(), Decimal.ONE, Decimal.ZERO, 0), emptyMap()),
        ).isNull()
    }

    @Test
    fun `picks the first weekday of each month, and skips New Year's Day`() {
        assertThat(isRebalanceDay(LocalDate.parse("2026-11-02"))).isTrue()
        assertThat(isRebalanceDay(LocalDate.parse("2026-11-01"))).isFalse()
        assertThat(isRebalanceDay(LocalDate.parse("2026-11-03"))).isFalse()
        assertThat(isRebalanceDay(LocalDate.parse("2026-12-01"))).isTrue()
        assertThat(isRebalanceDay(LocalDate.parse("2027-01-01"))).isFalse()
        assertThat(isRebalanceDay(LocalDate.parse("2027-01-04"))).isTrue()
    }
}
