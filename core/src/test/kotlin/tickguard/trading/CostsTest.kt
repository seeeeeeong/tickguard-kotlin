package tickguard.trading

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tickguard.testing.decimal

class CostsTest {
    private val costs =
        CostModel(commission = decimal("0.00015"), sellTax = decimal("0.0018"), slippage = decimal("0.001"))

    @Test
    fun `moves the fill against the trader on both sides`() {
        assertThat(costs.fillPrice(Side.BUY, decimal("70000"))).isEqualTo(decimal("70070"))
        assertThat(costs.fillPrice(Side.SELL, decimal("70000"))).isEqualTo(decimal("69930"))
    }

    @Test
    fun `taxes sells only`() {
        assertThat(costs.fees(Side.BUY, decimal("1000000"))).isEqualTo(decimal("150"))
        assertThat(costs.fees(Side.SELL, decimal("1000000"))).isEqualTo(decimal("1950"))
    }
}
