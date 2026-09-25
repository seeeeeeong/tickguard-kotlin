package tickguard.trading

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tickguard.orders.Execution
import tickguard.orders.Order
import tickguard.stream.Decimal
import java.time.Instant
import java.time.LocalDate

class DipTest {
    private fun d(text: String) = Decimal.parse(text, "test")

    private val start = LocalDate.parse("2025-01-01")

    /** [closes] as daily bars, high equal to close. */
    private fun bars(
        code: String,
        closes: List<Double>,
    ) = closes.mapIndexed { i, c ->
        val price = d("%.4f".format(java.util.Locale.US, c))
        Bar(code, start.plusDays(i.toLong()), price, price, price, price, Decimal.ONE)
    }

    /** 230 days rising from 100 to 200, then five days down to [last]: a dip in an uptrend. */
    private fun dip(last: Double = 172.0) =
        (0 until 230).map { 100.0 + it * 100.0 / 229 } + listOf(195.0, 188.0, 181.0, 176.0, last)

    private fun flat(price: Double) = List(235) { price }

    private val sleeve =
        Sleeve(
            "D",
            "D",
            d("600"),
            listOf("AAA", "BBB", "SPY"),
            ::BuyAndHold,
            d("0.05"),
            d("0.40"),
            LossAction.STOP_BUYING,
            DipRules(),
        )

    private fun position(
        cash: String,
        vararg held: Pair<String, String>,
    ) = SleevePosition(held.associate { (c, q) -> c to d(q) }, d(cash), Decimal.ZERO, 0)

    private fun propose(
        position: SleevePosition,
        lots: Map<String, Lot>,
        vararg series: Pair<String, List<Double>>,
    ) = proposeDip(sleeve, position, lots, series.associate { (c, closes) -> c to bars(c, closes) }, DipRules())!!

    private fun List<ProposedTrade>.summary() = map { "${it.side} ${it.code} ${it.value.format(2)}" }

    @Test
    fun `reads a fall in an uptrend as a dip, and a fall in a downtrend as nothing`() {
        assertThat(signal(bars("AAA", dip()), DipRules())).isNotNull()
        assertThat(signal(bars("AAA", dip().reversed()), DipRules())).isNull()
        assertThat(signal(bars("AAA", flat(100.0)), DipRules())).isNull()
    }

    @Test
    fun `measures relative strength as the backtest did`() {
        assertThat(rsi(List(20) { d("${100 + it}") }, 14)).isEqualTo(Decimal.HUNDRED)
        assertThat(rsi(List(10) { d("100") }, 14)).isNull()
    }

    @Test
    fun `buys a tranche of a dip and parks the rest in SPY`() {
        val trades =
            propose(
                position("600"),
                emptyMap(),
                "AAA" to dip(),
                "BBB" to flat(50.0),
                "SPY" to flat(500.0),
            ).trades

        assertThat(trades.summary()).containsExactly("BUY AAA 100.00", "BUY SPY 498.00")
    }

    @Test
    fun `takes the profit at eight percent and the loss at twenty`() {
        val lot = Lot(d("1"), d("100"), d("100"), 1)

        val profit =
            propose(
                position("0", "AAA" to "1"),
                mapOf("AAA" to lot),
                "AAA" to flat(108.0),
                "SPY" to flat(500.0),
            )
        val loss = propose(position("0", "AAA" to "1"), mapOf("AAA" to lot), "AAA" to flat(80.0), "SPY" to flat(500.0))
        val hold = propose(position("0", "AAA" to "1"), mapOf("AAA" to lot), "AAA" to flat(95.0), "SPY" to flat(500.0))

        assertThat(profit.trades.summary()).containsExactly("SELL AAA 108.00")
        assertThat(loss.trades.summary()).containsExactly("SELL AAA 80.00")
        assertThat(hold.trades).isEmpty()
    }

    @Test
    fun `averages down once, eight percent under the first fill, and never twice`() {
        val once = Lot(d("1"), d("100"), d("100"), 1)
        val twice = Lot(d("2"), d("192"), d("100"), 2)

        val add =
            propose(position("300", "AAA" to "1"), mapOf("AAA" to once), "AAA" to flat(91.0), "SPY" to flat(500.0))
        val again =
            propose(
                position("300", "AAA" to "2"),
                mapOf("AAA" to twice),
                "AAA" to flat(91.0),
                "SPY" to flat(500.0),
            )

        assertThat(add.trades.summary()).containsExactly("BUY AAA 100.00", "BUY SPY 198.00")
        assertThat(again.trades.summary()).containsExactly("BUY SPY 298.00")
    }

    @Test
    fun `sells enough parked SPY to pay for a dip when cash is short`() {
        val trades = propose(position("5", "SPY" to "1"), emptyMap(), "AAA" to dip(), "SPY" to flat(500.0)).trades

        // (100 - 3) x 1.03 = 99.91 dollars of SPY at 500.
        assertThat(trades.summary()).containsExactly("SELL SPY 99.91", "BUY AAA 100.00")
    }

    @Test
    fun `drops a buy that cash and all of the parking cannot pay for, and then sells no parking`() {
        val trades = propose(position("5", "SPY" to "0.1"), emptyMap(), "AAA" to dip(), "SPY" to flat(500.0)).trades

        assertThat(trades).isEmpty()
    }

    @Test
    fun `only sells past the loss limit`() {
        val lot = Lot(d("1"), d("100"), d("100"), 1)
        val proposal =
            propose(
                position("100", "AAA" to "1"),
                mapOf("AAA" to lot),
                "AAA" to flat(85.0),
                "BBB" to dip(),
                "SPY" to flat(500.0),
            )

        assertThat(proposal.stopped).isEqualTo(LossAction.STOP_BUYING)
        assertThat(proposal.trades).isEmpty()
    }

    @Test
    fun `holds no more positions than its slots`() {
        val lot = Lot(d("1"), d("100"), d("100"), 1)
        val full = mapOf("X1" to lot, "X2" to lot, "X3" to lot)
        val wide = sleeve.copy(universe = listOf("X1", "X2", "X3", "AAA", "SPY"))
        val proposal =
            proposeDip(
                wide,
                position("300", "X1" to "1", "X2" to "1", "X3" to "1"),
                full,
                listOf("X1", "X2", "X3").associateWith { bars(it, flat(100.0)) } + ("AAA" to bars("AAA", dip())) +
                    ("SPY" to bars("SPY", flat(500.0))),
                DipRules(),
            )!!

        assertThat(proposal.trades.map { it.code }).doesNotContain("AAA")
    }

    @Test
    fun `keeps each position's cost and first price from its fills, and starts over once sold out`() {
        fun order(
            id: String,
            side: String,
            quantity: String,
            price: String,
            at: Long,
        ) = Order(
            id,
            "AAA",
            side,
            "MARKET",
            "DAY",
            "FILLED",
            null,
            d(quantity),
            null,
            "USD",
            Instant.ofEpochSecond(at),
            null,
            Execution(d(quantity), d(price), d(quantity) * d(price), d("0.1"), null, null),
        )

        val open = lots(listOf(order("1", "BUY", "1", "100", 1), order("2", "BUY", "1", "90", 2)))
        assertThat(
            open.getValue("AAA").let {
                Triple(it.quantity, it.firstPrice, it.buys)
            },
        ).isEqualTo(Triple(d("2"), d("100"), 2))
        assertThat(open.getValue("AAA").cost).isEqualTo(d("190.2"))

        val reopened =
            lots(
                listOf(
                    order("1", "BUY", "1", "100", 1),
                    order("2", "SELL", "1", "110", 2),
                    order("3", "BUY", "2", "50", 3),
                ),
            )
        assertThat(
            reopened.getValue("AAA").let {
                Triple(it.quantity, it.firstPrice, it.buys)
            },
        ).isEqualTo(Triple(d("2"), d("50"), 1))
    }

    @Test
    fun `never trades GOOGL, and parks in SPY`() {
        val dipSleeve = TestSleeves.ALL.single { it.dip != null }

        assertThat(dipSleeve.universe).doesNotContain("GOOGL").contains("SPY", "AAPL")
        assertThat(dipSleeve.capital).isEqualTo(d("733.98"))
    }
}
