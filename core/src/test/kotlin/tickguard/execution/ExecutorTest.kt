package tickguard.execution

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tickguard.orders.Recorded
import tickguard.orders.ScriptedOrders
import tickguard.testing.decimal
import tickguard.trading.Side

class ExecutorTest {
    private fun buy(symbol: String) =
        OrderRequest("tg-20261102-A-B-$symbol", "A", symbol, Side.BUY, decimal("10"), null)

    private val tags = TaggingOrders()

    @Test
    fun `tags every accepted order with its sleeve and carries on past a refusal`() =
        runTest {
            val answers =
                mapOf(
                    "SPY" to PlaceOutcome.Placed("o1"),
                    "QQQ" to PlaceOutcome.Refused(422, "insufficient-buying-power", "no buying power"),
                    "TLT" to PlaceOutcome.Placed("o3"),
                )
            val executor = Executor({ answers.getValue(it.symbol) }, tags)

            val result = executor.run(OrderPlan(listOf(buy("SPY"), buy("QQQ"), buy("TLT")), emptyList(), emptyList()))

            assertThat(result.placed.map { it.second }).containsExactly("o1", "o3")
            assertThat(
                result.refused
                    .single()
                    .second.code,
            ).isEqualTo("insufficient-buying-power")
            assertThat(tags.tagged).containsExactly("o1" to "A", "o3" to "A")
            assertThat(executor.halted).isNull()
        }

    @Test
    fun `halts at an unknown outcome, sends nothing after it, and nothing ever again`() =
        runTest {
            val sent = mutableListOf<String>()
            val executor =
                Executor({ request ->
                    sent += request.symbol
                    if (request.symbol ==
                        "QQQ"
                    ) {
                        PlaceOutcome.Unknown("timeout")
                    } else {
                        PlaceOutcome.Placed("o-${request.symbol}")
                    }
                }, tags)

            val first = executor.run(OrderPlan(listOf(buy("SPY"), buy("QQQ"), buy("TLT")), emptyList(), emptyList()))
            val second = executor.run(OrderPlan(listOf(buy("GLD")), emptyList(), emptyList()))

            assertThat(sent).containsExactly("SPY", "QQQ")
            assertThat(first.haltedAt?.second).isEqualTo("timeout")
            assertThat(executor.halted).isEqualTo("tg-20261102-A-B-QQQ: timeout")
            assertThat(second.placed).isEmpty()
        }

    @Test
    fun `halts when an accepted order's sleeve cannot be recorded, since its money would be spent again`() =
        runTest {
            val sent = mutableListOf<String>()
            val broken =
                object : tickguard.orders.OrderStore by ScriptedOrders(Recorded.REPEAT) {
                    override suspend fun tagOrder(
                        orderId: String,
                        sleeve: String,
                    ): Unit = error("database is down")
                }
            val executor =
                Executor({
                    sent += it.symbol
                    PlaceOutcome.Placed("o-${it.symbol}")
                }, broken)

            val result = executor.run(OrderPlan(listOf(buy("SPY"), buy("QQQ")), emptyList(), emptyList()))

            assertThat(sent).containsExactly("SPY")
            assertThat(result.placed.map { it.second }).containsExactly("o-SPY")
            assertThat(result.haltedAt?.second).isEqualTo("sleeve not recorded")
            assertThat(executor.halted).contains("o-SPY").contains("database is down")
        }

    @Test
    fun `never sends a dry run's orders`() =
        runTest {
            val sent = mutableListOf<String>()
            val executor =
                Executor({
                    sent += it.symbol
                    PlaceOutcome.Placed("x")
                }, tags)

            executor.run(OrderPlan(emptyList(), listOf(buy("SPY")), emptyList()))

            assertThat(sent).isEmpty()
        }
}

/** Records tags; answers everything else as an empty ledger. */
private class TaggingOrders : tickguard.orders.OrderStore by ScriptedOrders(Recorded.REPEAT) {
    val tagged = mutableListOf<Pair<String, String>>()

    override suspend fun tagOrder(
        orderId: String,
        sleeve: String,
    ) {
        tagged += orderId to sleeve
    }
}
