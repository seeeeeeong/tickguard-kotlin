package tickguard.orders

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tickguard.testing.order
import java.io.IOException
import java.time.Instant
import java.time.InstantSource

/** Answers each write with the next outcome given, and remembers what it was asked. */
class ScriptedOrders(
    private vararg val outcomes: Any,
    private val open: List<Order> = emptyList(),
) : OrderStore {
    val written = mutableListOf<Pair<String, OrderSource>>()

    override suspend fun recordOrder(
        order: Order,
        event: String?,
        source: OrderSource,
        seenAt: Instant,
    ): Recorded {
        val outcome = outcomes[minOf(written.size, outcomes.size - 1)]
        written += "${order.orderId}:${order.status}" to source
        if (outcome is Exception) throw outcome
        return outcome as Recorded
    }

    override suspend fun order(orderId: String) = null

    override suspend fun openOrders() = open

    override suspend fun orderHistory(orderId: String) = emptyList<OrderChange>()
}

class OrderRecorderTest {
    private val clock = InstantSource { Instant.EPOCH }

    @Test
    fun `writes changes in the order they came and announces only a new state`() =
        runTest {
            val store = ScriptedOrders(Recorded.NEW_STATE, Recorded.REPEAT, Recorded.STALE)
            val announced = mutableListOf<String>()
            val recorder = OrderRecorder(store, clock, { order, _ -> announced += order.status }, { _, _ -> })
            val job = recorder.start(backgroundScope)

            recorder.offer(order(status = "FILLED"), "FILL", OrderSource.STREAM)
            recorder.offer(order(status = "FILLED"), null, OrderSource.RESYNC)
            recorder.offer(order(status = "PARTIAL_FILLED", filled = "3"), "PARTIAL_FILL", OrderSource.STREAM)
            runCurrent()

            assertThat(store.written.map { it.first }).containsExactly("o1:FILLED", "o1:FILLED", "o1:PARTIAL_FILLED")
            assertThat(announced).containsExactly("FILLED")
            assertThat(
                recorder.stats(),
            ).isEqualTo(OrderRecorderStats(newStates = 1, stale = 1, repeats = 1, failures = 0))
            job.cancel()
        }

    @Test
    fun `records a resync find without announcing it when told not to`() =
        runTest {
            val announced = mutableListOf<String>()
            val recorder =
                OrderRecorder(
                    ScriptedOrders(Recorded.NEW_STATE),
                    clock,
                    { o, _ -> announced += o.orderId },
                    { _, _ -> },
                )
            val job = recorder.start(backgroundScope)

            recorder.offer(order(), null, OrderSource.RESYNC, announce = false)
            runCurrent()

            assertThat(announced).isEmpty()
            assertThat(recorder.stats().newStates).isEqualTo(1)
            job.cancel()
        }

    @Test
    fun `reports a failed write with its order and keeps going`() =
        runTest {
            val failed = mutableListOf<String>()
            val store = ScriptedOrders(IOException("database is down"), Recorded.NEW_STATE)
            val recorder =
                OrderRecorder(store, clock, { _, _ -> }, { error, o ->
                    failed +=
                        "${o.orderId}: ${error.message}"
                })
            val job = recorder.start(backgroundScope)

            recorder.offer(order(orderId = "a"), "FILL", OrderSource.STREAM)
            recorder.offer(order(orderId = "b"), "FILL", OrderSource.STREAM)
            runCurrent()

            assertThat(failed).containsExactly("a: database is down")
            assertThat(recorder.stats().newStates).isEqualTo(1)
            job.cancel()
        }
}
