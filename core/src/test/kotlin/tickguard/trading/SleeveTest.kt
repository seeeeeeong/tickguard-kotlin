package tickguard.trading

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tickguard.orders.Execution
import tickguard.orders.Order
import tickguard.testing.decimal
import tickguard.testing.order
import java.time.Instant

class SleeveTest {
    private val start = Instant.parse("2026-09-25T13:00:00Z")

    private fun sleeve(
        id: String,
        vararg universe: String,
    ) = Sleeve(
        id,
        id,
        decimal("100"),
        universe.toList(),
        ::BuyAndHold,
        decimal("0.05"),
        decimal("0.25"),
        LossAction.STOP_BUYING,
    )

    private val a = sleeve("A", "SPY", "TLT")
    private val c = sleeve("C", "AAPL", "AMZN")

    private fun filled(
        id: String,
        symbol: String,
        side: String = "BUY",
        quantity: String = "0.5",
        amount: String = "30",
        commission: String = "0.03",
        at: Instant = start.plusSeconds(600),
    ): Order =
        order(orderId = id, symbol = symbol, orderedAt = at).copy(
            side = side,
            quantity = decimal(quantity),
            execution =
                Execution(
                    filledQuantity = decimal(quantity),
                    averageFilledPrice = decimal(amount) / decimal(quantity),
                    filledAmount = decimal(amount),
                    commission = decimal(commission),
                    tax = decimal("0"),
                    settlementDate = null,
                ),
        )

    @Test
    fun `gives a sleeve the orders only it could have placed, and guesses nothing the account also holds`() {
        val orders =
            listOf(
                filled("1", "SPY"),
                filled("2", "AAPL"),
                // The account held AMZN before the test: untagged, this is a personal trade.
                filled("3", "AMZN"),
                // Tagged by the execution module: C's, although AMZN is also personal.
                filled("4", "AMZN"),
                // Before the test began.
                filled("5", "TLT", at = start.minusSeconds(60)),
                // In no sleeve's universe.
                filled("6", "NVDA"),
            )

        val owned = attribute(orders, listOf(a, c), tagged = mapOf("4" to "C"), personal = setOf("AMZN"), since = start)

        assertThat(owned.mapValues { (_, list) -> list.map { it.orderId } }).isEqualTo(
            mapOf(
                "A" to listOf("1"),
                "C" to listOf("2", "4"),
            ),
        )
    }

    @Test
    fun `holds what was bought less what was sold, with cash net of fees`() {
        val orders =
            listOf(
                filled("1", "SPY", quantity = "0.04", amount = "30.69", commission = "0.03"),
                filled("2", "TLT", quantity = "0.38", amount = "30.69", commission = "0.03"),
                filled(
                    "3",
                    "TLT",
                    side = "SELL",
                    quantity = "0.38",
                    amount = "31",
                    commission = "0.03",
                    at = start.plusSeconds(900),
                ),
            )

        val position = position(a, orders)

        assertThat(position.holdings).isEqualTo(mapOf("SPY" to decimal("0.04")))
        assertThat(position.cash).isEqualTo(decimal("100") - decimal("30.72") - decimal("30.72") + decimal("30.97"))
        assertThat(position.feesPaid).isEqualTo(decimal("0.09"))
        assertThat(position.fills).isEqualTo(3)
    }

    @Test
    fun `counts nothing for an order that has not filled`() {
        val pending = filled("1", "SPY").copy(execution = Execution(decimal("0"), null, null, null, null, null))

        assertThat(position(a, listOf(pending))).isEqualTo(SleevePosition(emptyMap(), decimal("100"), decimal("0"), 0))
    }
}
