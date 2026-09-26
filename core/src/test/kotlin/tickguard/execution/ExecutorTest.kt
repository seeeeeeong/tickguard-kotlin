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
    fun `journals each order around its request, keeps the unaccounted one, and starts halted on it`() =
        runTest {
            val journal = MemoryJournal()
            val first =
                Executor({
                    if (it.symbol == "QQQ") PlaceOutcome.Unknown("timeout") else PlaceOutcome.Placed("o-${it.symbol}")
                }, tags, journal)

            first.run(OrderPlan(listOf(buy("SPY"), buy("QQQ")), emptyList(), emptyList()))

            assertThat(journal.pending()).containsExactly("tg-20261102-A-B-QQQ")
            val restarted = Executor({ PlaceOutcome.Placed("never") }, tags, journal)
            assertThat(restarted.halted).contains("tg-20261102-A-B-QQQ")
            assertThat(restarted.run(OrderPlan(listOf(buy("GLD")), emptyList(), emptyList())).placed).isEmpty()
        }

    @Test
    fun `sends nothing when the journal cannot be written`() =
        runTest {
            val sent = mutableListOf<String>()
            val unwritable =
                object : PlacementJournal by NoJournal {
                    override fun begin(request: OrderRequest): Unit = error("disk full")
                }
            val executor =
                Executor({
                    sent += it.symbol
                    PlaceOutcome.Placed("x")
                }, tags, unwritable)

            executor.run(OrderPlan(listOf(buy("SPY")), emptyList(), emptyList()))

            assertThat(sent).isEmpty()
            assertThat(executor.halted).contains("disk full")
        }

    @Test
    fun `halts when an accounted order's journal entry cannot be removed`() =
        runTest {
            val stuck =
                object : PlacementJournal by NoJournal {
                    override fun done(request: OrderRequest): Unit = error("read-only")
                }
            val executor = Executor({ PlaceOutcome.Placed("o-${it.symbol}") }, tags, stuck)

            val result = executor.run(OrderPlan(listOf(buy("SPY"), buy("QQQ")), emptyList(), emptyList()))

            assertThat(result.placed.map { it.second }).containsExactly("o-SPY")
            assertThat(result.haltedAt?.second).isEqualTo("journal not cleared")
            assertThat(executor.halted).contains("read-only")
        }

    @Test
    fun `sends none of a sleeve's buys once one of its sales is refused`() =
        runTest {
            val sent = mutableListOf<String>()
            val sell = OrderRequest("tg-20261102-D-S-SPY", "D", "SPY", Side.SELL, null, decimal("0.2"))
            val otherSleeve = OrderRequest("tg-20261102-A-B-GLD", "A", "GLD", Side.BUY, decimal("10"), null)
            val dipBuy = OrderRequest("tg-20261102-D-B-AAPL", "D", "AAPL", Side.BUY, decimal("100"), null)
            val executor =
                Executor({
                    sent += it.symbol
                    if (it.side ==
                        Side.SELL
                    ) {
                        PlaceOutcome.Refused(422, "insufficient-quantity", "no")
                    } else {
                        PlaceOutcome.Placed("o")
                    }
                }, tags)

            val result = executor.run(OrderPlan(listOf(sell, otherSleeve, dipBuy), emptyList(), emptyList()))

            assertThat(sent).containsExactly("SPY", "GLD")
            assertThat(
                result.refused.map {
                    it.first.symbol to it.second.code
                },
            ).containsExactly("SPY" to "insufficient-quantity", "AAPL" to "not-sent")
            assertThat(executor.halted).isNull()
        }

    @Test
    fun `sends a sleeve's buys only once its sales have filled, and only as far as they brought`() =
        runTest {
            val sell = OrderRequest("tg-20261102-D-S-SPY", "D", "SPY", Side.SELL, null, decimal("0.2"))
            val dipBuy = OrderRequest("tg-20261102-D-B-AAPL", "D", "AAPL", Side.BUY, decimal("100"), null)
            val otherSleeve = OrderRequest("tg-20261102-A-B-GLD", "A", "GLD", Side.BUY, decimal("10"), null)
            val waitedFor = mutableListOf<List<String>>()

            suspend fun run(proceeds: String?): ExecutionResult {
                val executor =
                    Executor({ PlaceOutcome.Placed("o-${it.symbol}") }, tags, NoJournal) { ids ->
                        waitedFor += ids
                        proceeds?.let(::decimal)
                    }
                val plan =
                    OrderPlan(
                        listOf(sell, otherSleeve, dipBuy),
                        emptyList(),
                        emptyList(),
                        mapOf("D" to decimal("3")),
                    )
                return executor.run(plan)
            }

            val unfilled = run(proceeds = null)
            // SPY gapped down: $3 of cash and $95 of proceeds do not cover a $100 buy.
            val short = run(proceeds = "95")
            val enough = run(proceeds = "97")

            assertThat(unfilled.placed.map { it.first.symbol }).containsExactly("SPY", "GLD")
            assertThat(unfilled.refused.map { it.first.symbol }).containsExactly("AAPL")
            assertThat(short.refused.map { it.first.symbol }).containsExactly("AAPL")
            assertThat(enough.placed.map { it.first.symbol }).containsExactly("SPY", "GLD", "AAPL")
            assertThat(waitedFor).containsExactly(listOf("o-SPY"), listOf("o-SPY"), listOf("o-SPY"))
        }

    @Test
    fun `sends nothing more once the run is switched off part way`() =
        runTest {
            val sent = mutableListOf<String>()
            var live = true
            val executor =
                Executor({
                    sent += it.symbol
                    // A person switches daily orders off while the first order is in flight.
                    live = false
                    PlaceOutcome.Placed("o-${it.symbol}")
                }, tags)

            val result = executor.run(OrderPlan(listOf(buy("SPY"), buy("QQQ")), emptyList(), emptyList())) { live }

            assertThat(sent).containsExactly("SPY")
            assertThat(
                result.refused.map {
                    it.first.symbol to it.second.message
                },
            ).containsExactly("QQQ" to "switched off during the run")
        }

    @Test
    fun `ends the run at the first switch off, even if switched back on, and asks again after waiting on a sale`() =
        runTest {
            val sent = mutableListOf<String>()
            val sell = OrderRequest("tg-20261102-D-S-SPY", "D", "SPY", Side.SELL, null, decimal("0.2"))
            val dipBuy = OrderRequest("tg-20261102-D-B-AAPL", "D", "AAPL", Side.BUY, decimal("100"), null)
            val answers = ArrayDeque(listOf(true, true, false, true, true))
            val executor =
                Executor({
                    sent += it.symbol
                    PlaceOutcome.Placed("o-${it.symbol}")
                }, tags, NoJournal) { decimal("200") }

            // Live for the sale, then switched off while the buy waited on its fill, then on again.
            val result =
                executor.run(
                    OrderPlan(
                        listOf(sell, dipBuy, buy("QQQ")),
                        emptyList(),
                        emptyList(),
                        mapOf("D" to decimal("0")),
                    ),
                ) {
                    answers.removeFirstOrNull() ?: true
                }

            assertThat(sent).containsExactly("SPY")
            assertThat(result.refused.map { it.first.symbol }).containsExactly("AAPL", "QQQ")
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

/** The journal in memory, shared between two executors as a restart would share the directory. */
private class MemoryJournal : PlacementJournal {
    private val open = LinkedHashSet<String>()

    override fun begin(request: OrderRequest) {
        open += request.clientOrderId
    }

    override fun done(request: OrderRequest) {
        open -= request.clientOrderId
    }

    override fun pending(): List<String> = open.toList()
}
