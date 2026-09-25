package tickguard.trading

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tickguard.stream.Decimal
import tickguard.testing.decimal

class ProposedTradeTest {
    private val band = decimal("0.05")

    @Test
    fun `buys a fresh sleeve into its targets, in fractional shares`() {
        val trades =
            proposeTrades(
                targets = mapOf("SPY" to decimal("0.5"), "TLT" to decimal("0.5")),
                held = emptyMap(),
                prices = mapOf("SPY" to decimal("700"), "TLT" to decimal("80")),
                capital = decimal("150"),
                band = band,
            )

        assertThat(trades.map { Triple(it.code, it.side, it.quantity.toPlainString()) })
            .containsExactly(Triple("SPY", Side.BUY, "0.107143"), Triple("TLT", Side.BUY, "0.9375"))
        assertThat(trades.first().value).isEqualTo(decimal("75"))
    }

    @Test
    fun `leaves a drift inside the band alone, trades one outside it, and sells before it buys`() {
        // Worth 100: A at 54 (target 50, inside the band), B at 30 (target 50), C at 16 (target 0).
        val trades =
            proposeTrades(
                targets = mapOf("A" to decimal("0.5"), "B" to decimal("0.5")),
                held = mapOf("A" to decimal("54"), "B" to decimal("30"), "C" to decimal("16")),
                prices = mapOf("A" to Decimal.ONE, "B" to Decimal.ONE, "C" to Decimal.ONE),
                capital = decimal("100"),
                band = band,
            )

        assertThat(trades.map { it.code to it.side }).containsExactly("C" to Side.SELL, "B" to Side.BUY)
        assertThat(trades.last().quantity).isEqualTo(decimal("20"))
    }

    @Test
    fun `sells out a holding whose target went to zero, however small`() {
        val trades =
            proposeTrades(
                targets = mapOf("A" to Decimal.ZERO),
                held = mapOf("A" to decimal("2")),
                prices = mapOf("A" to Decimal.ONE),
                capital = decimal("100"),
                band = band,
            )

        assertThat(trades.single().side).isEqualTo(Side.SELL)
        assertThat(trades.single().quantity).isEqualTo(decimal("2"))
    }
}
