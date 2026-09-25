package tickguard.orders

import tickguard.rest.RestClient
import tickguard.time.SEOUL
import java.time.Instant
import java.time.InstantSource
import java.time.LocalDate

data class ResyncResult(
    val orders: Int,
    val unreadable: List<String>,
)

/**
 * Catches the ledger up after a (re)connect. The stream is lossless only
 * within a connection and never replays the gap, so the orders API is asked
 * for everything still working and everything closed since the oldest day
 * that could matter: today in Seoul, or earlier if the ledger still tracks an
 * order placed before then.
 *
 * Everything found goes through the recorder, which drops what it has seen.
 * Only two kinds of change are announced: one to an order the ledger was
 * tracking as working, and one to an order placed after [run]'s `gapStart`.
 * The rest is history the ledger did not have yet — on a first run, the
 * day's earlier trades — and is recorded without a message for each.
 */
class OrderResync(
    private val rest: RestClient,
    private val store: OrderStore,
    private val recorder: OrderRecorder,
    private val clock: InstantSource,
) {
    suspend fun run(gapStart: Instant): ResyncResult {
        val tracked = store.openOrders().associateBy { it.orderId }
        val today = LocalDate.ofInstant(clock.instant(), SEOUL)
        val from = (tracked.values.map { LocalDate.ofInstant(it.orderedAt, SEOUL) } + today).min()

        val closed = fetchOrders(rest, "CLOSED", from)
        val open = fetchOrders(rest, "OPEN")
        val found = closed.orders + open.orders
        for (order in found) {
            val announce = order.orderId in tracked || !order.orderedAt.isBefore(gapStart)
            recorder.offer(order, event = null, source = OrderSource.RESYNC, announce = announce)
        }
        return ResyncResult(found.size, closed.unreadable + open.unreadable)
    }
}
