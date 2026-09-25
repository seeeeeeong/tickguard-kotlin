package tickguard.trading

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tickguard.testing.decimal

class PortfolioTest {
    @Test
    fun `books realised gain against average cost, fees included`() {
        val portfolio = Portfolio(decimal("1000"))
        portfolio.buy("A", 10, decimal("10"), decimal("1"))
        portfolio.buy("A", 10, decimal("20"), decimal("1"))

        portfolio.sell("A", 10, decimal("30"), decimal("2"))

        // Average cost 15.1 a share with fees; sold ten for 298 net.
        assertThat(portfolio.realized).isEqualTo(decimal("147"))
        assertThat(portfolio.quantity("A")).isEqualTo(10)
        assertThat(portfolio.cash).isEqualTo(decimal("1000") - decimal("302") + decimal("298"))
        assertThat(portfolio.value(mapOf("A" to decimal("30")))).isEqualTo(portfolio.cash + decimal("300"))
    }

    @Test
    fun `refuses to spend cash it does not have, or sell shares it does not hold`() {
        val portfolio = Portfolio(decimal("100"))

        assertThatThrownBy { portfolio.buy("A", 10, decimal("10"), decimal("0.01")) }.hasMessageContaining("costs")
        assertThatThrownBy { portfolio.sell("A", 1, decimal("10"), decimal("0")) }.hasMessageContaining("holding 0")
    }
}
