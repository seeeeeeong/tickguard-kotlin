package tickguard.orders

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tickguard.testing.StubRest
import tickguard.testing.order
import java.time.Instant
import java.time.InstantSource

class OrderResyncTest {
    /** 2026-09-25 14:00 in Seoul. */
    private val now = Instant.parse("2026-09-25T05:00:00Z")
    private val clock = InstantSource { now }

    private fun page(vararg orders: Order) =
        """{"result":{"orders":[${orders.joinToString(",") { it.json() }}],"nextCursor":null,"hasNext":false}}"""

    private fun Order.json() =
        """{"orderId":"$orderId","symbol":"$symbol","side":"$side","orderType":"$orderType","timeInForce":"DAY",""" +
            """"status":"$status","price":null,"quantity":"${quantity.toPlainString()}","orderAmount":null,""" +
            """"currency":"USD","orderedAt":"$orderedAt","canceledAt":null,"execution":{"filledQuantity":""" +
            """"${execution.filledQuantity.toPlainString()}","averageFilledPrice":null,"filledAmount":null,""" +
            """"commission":null,"tax":null,"settlementDate":null}}"""

    @Test
    fun `announces changes to tracked orders and to orders placed in the gap, and only records the rest`() =
        runTest {
            val gapStart = Instant.parse("2026-09-25T04:00:00Z")
            val tracked =
                order(
                    orderId = "tracked",
                    status = "PENDING",
                    filled = "0",
                    orderedAt = Instant.parse("2026-09-25T01:00:00Z"),
                )
            val closed =
                page(
                    // Tracked as working, filled while the socket was down.
                    order(orderId = "tracked", status = "FILLED", orderedAt = tracked.orderedAt),
                    // Filled this morning, before this process was listening: history, not news.
                    order(orderId = "morning", status = "FILLED", orderedAt = Instant.parse("2026-09-25T00:30:00Z")),
                    // Placed and filled inside the gap.
                    order(orderId = "gap", status = "FILLED", orderedAt = Instant.parse("2026-09-25T04:30:00Z")),
                )
            val store = ScriptedOrders(Recorded.NEW_STATE, open = listOf(tracked))
            val announced = mutableListOf<String>()
            val recorder =
                OrderRecorder(store, clock, { o, source -> announced += "${o.orderId}/$source" }, { _, _ -> })
            val job = recorder.start(backgroundScope)

            val result = OrderResync(StubRest(closed, page()), store, recorder, clock).run(gapStart)
            runCurrent()

            assertThat(result.orders).isEqualTo(3)
            assertThat(store.written.map { it.first }).containsExactly("tracked:FILLED", "morning:FILLED", "gap:FILLED")
            assertThat(announced).containsExactly("tracked/RESYNC", "gap/RESYNC")
            job.cancel()
        }

    @Test
    fun `reaches back to the oldest order the ledger still tracks`() =
        runTest {
            val lingering =
                order(
                    orderId = "old",
                    status = "PENDING",
                    filled = "0",
                    orderedAt = Instant.parse("2026-09-22T06:00:00Z"),
                )
            val rest = StubRest(page(), page())
            val store = ScriptedOrders(Recorded.REPEAT, open = listOf(lingering))

            OrderResync(rest, store, OrderRecorder(store, clock, { _, _ -> }, { _, _ -> }), clock).run(now)

            assertThat(rest.asked.first().query).containsEntry("status", "CLOSED").containsEntry("from", "2026-09-22")
            assertThat(rest.asked.last().query).containsEntry("status", "OPEN")
        }

    @Test
    fun `asks for today in Seoul when nothing older is tracked`() =
        runTest {
            // 23:30 UTC on the 24th is already the 25th in Seoul.
            val lateUtc = InstantSource { Instant.parse("2026-09-24T23:30:00Z") }
            val rest = StubRest(page(), page())
            val store = ScriptedOrders(Recorded.REPEAT)

            OrderResync(rest, store, OrderRecorder(store, lateUtc, { _, _ -> }, { _, _ -> }), lateUtc).run(now)

            assertThat(rest.asked.first().query).containsEntry("from", "2026-09-25")
        }
}
