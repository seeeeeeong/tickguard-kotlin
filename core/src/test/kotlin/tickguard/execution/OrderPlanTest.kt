package tickguard.execution

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tickguard.stream.Decimal
import tickguard.testing.decimal
import tickguard.trading.BuyAndHold
import tickguard.trading.LossAction
import tickguard.trading.ProposedTrade
import tickguard.trading.Side
import tickguard.trading.Sleeve
import tickguard.trading.SleeveMode
import tickguard.trading.SleevePosition
import tickguard.trading.SleeveProposal
import java.time.LocalDate

class OrderPlanTest {
    private val day = LocalDate.parse("2026-11-02")
    private val limits = ExecutionLimits(maxBuys = decimal("219.2"), maxOrdersPerRun = 20)

    private fun sleeve(
        id: String,
        vararg universe: String,
        capital: String = "150",
    ) = Sleeve(
        id,
        id,
        decimal(capital),
        universe.toList(),
        ::BuyAndHold,
        decimal("0.05"),
        decimal("0.25"),
        LossAction.STOP_BUYING,
    )

    private fun trade(
        code: String,
        side: Side,
        value: String,
        quantity: String = "1",
    ) = ProposedTrade(code, side, decimal(quantity), decimal(value), decimal("1"), Decimal.ZERO, Decimal.ZERO)

    private fun proposal(
        sleeve: Sleeve,
        vararg trades: ProposedTrade,
        holdings: Map<String, Decimal> = emptyMap(),
    ) = SleeveProposal(
        sleeve,
        SleevePosition(holdings, Decimal.ZERO, Decimal.ZERO, 0),
        decimal("150"),
        emptyMap(),
        trades.toList(),
        null,
        day,
    )

    @Test
    fun `sends live sleeves' orders, sells first, buys by cents rounded down and sells by six places rounded down`() {
        val a = sleeve("A", "SPY", "TLT")
        val plan =
            planOrders(
                listOf(
                    proposal(
                        a,
                        trade("SPY", Side.BUY, "30.6999"),
                        trade("TLT", Side.SELL, "10", quantity = "0.3864059"),
                        holdings = mapOf("TLT" to decimal("0.4")),
                    ),
                ),
                mapOf("A" to SleeveMode.LIVE),
                limits,
                day,
            )

        assertThat(plan.live.map { Triple(it.side, it.amount?.toPlainString(), it.quantity?.toPlainString()) })
            .containsExactly(Triple(Side.SELL, null, "0.386405"), Triple(Side.BUY, "30.69", null))
        assertThat(plan.live.map { it.clientOrderId }).containsExactly("tg-20261102-A-S-TLT", "tg-20261102-A-B-SPY")
        assertThat(plan.dryRun).isEmpty()
    }

    @Test
    fun `builds but never sends a dry run, and leaves an off sleeve alone`() {
        val plan =
            planOrders(
                listOf(
                    proposal(sleeve("B", "VTI"), trade("VTI", Side.BUY, "8.77")),
                    proposal(sleeve("C", "AAPL"), trade("AAPL", Side.BUY, "10.96")),
                ),
                mapOf("B" to SleeveMode.DRY_RUN),
                limits,
                day,
            )

        assertThat(plan.live).isEmpty()
        assertThat(plan.dryRun.map { it.symbol }).containsExactly("VTI")
        assertThat(plan.skipped).containsExactly(SkippedTrade("C", "AAPL", "sleeve is off"))
    }

    @Test
    fun `never sells more than the sleeve holds`() {
        val plan =
            planOrders(
                listOf(
                    proposal(
                        sleeve("A", "SPY"),
                        trade("SPY", Side.SELL, "50", quantity = "0.9"),
                        holdings = mapOf("SPY" to decimal("0.25")),
                    ),
                ),
                mapOf("A" to SleeveMode.LIVE),
                limits,
                day,
            )

        assertThat(plan.live.single().quantity).isEqualTo(decimal("0.25"))
    }

    @Test
    fun `skips with its reason a buy under a dollar, over capital, off its symbols, or past the run's limits`() {
        val a = sleeve("A", "SPY", "QQQ", "TLT", capital = "40")
        val plan =
            planOrders(
                listOf(
                    proposal(
                        a,
                        trade("SPY", Side.BUY, "0.99"),
                        trade("QQQ", Side.BUY, "41"),
                        trade("NVDA", Side.BUY, "5"),
                        trade("TLT", Side.BUY, "30"),
                    ),
                ),
                mapOf("A" to SleeveMode.LIVE),
                ExecutionLimits(maxBuys = decimal("20"), maxOrdersPerRun = 20),
                day,
            )

        assertThat(plan.live).isEmpty()
        assertThat(plan.skipped.map { it.symbol to it.reason }).containsExactly(
            "SPY" to "buy under \$1",
            "QQQ" to "buy above the sleeve's capital",
            "NVDA" to "not in the sleeve's symbols",
            "TLT" to "over the run's buy limit",
        )
    }

    @Test
    fun `stops at the run's order count`() {
        val a = sleeve("A", "SPY", "QQQ")
        val plan =
            planOrders(
                listOf(proposal(a, trade("SPY", Side.BUY, "5"), trade("QQQ", Side.BUY, "5"))),
                mapOf("A" to SleeveMode.LIVE),
                ExecutionLimits(maxBuys = decimal("100"), maxOrdersPerRun = 1),
                day,
            )

        assertThat(plan.live.map { it.symbol }).containsExactly("SPY")
        assertThat(plan.skipped.single().reason).isEqualTo("over the run's order limit")
    }

    @Test
    fun `keeps a client order id inside the API's alphabet and length`() {
        val plan =
            planOrders(
                listOf(proposal(sleeve("C", "BRK.B"), trade("BRK.B", Side.BUY, "5"))),
                mapOf("C" to SleeveMode.LIVE),
                limits,
                day,
            )

        val id = plan.live.single().clientOrderId
        assertThat(id).isEqualTo("tg-20261102-C-B-BRK_B").matches("^[a-zA-Z0-9\\-_]+$")
        assertThat(id.length).isLessThanOrEqualTo(36)
    }

    @Test
    fun `gives each sleeve its cash as budget, less a dip sleeve's buffer`() {
        val dip = sleeve("D", "SPY").copy(dip = tickguard.trading.DipRules())
        val plan =
            planOrders(
                listOf(proposal(sleeve("A", "SPY")), proposal(dip)),
                mapOf("A" to SleeveMode.LIVE, "D" to SleeveMode.LIVE),
                limits,
                day,
            )

        assertThat(plan.cash.mapValues { it.value.format(2) }).containsExactly(
            org.assertj.core.api.Assertions
                .entry("A", "0.00"),
            org.assertj.core.api.Assertions
                .entry("D", "-2.00"),
        )
    }
}
