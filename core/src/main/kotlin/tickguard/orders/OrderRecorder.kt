package tickguard.orders

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.time.InstantSource

data class OrderRecorderStats(
    val newStates: Int,
    val stale: Int,
    val repeats: Int,
    val failures: Int,
)

/**
 * Writes order changes to the ledger one at a time, in the order they came.
 *
 * The stream and the resync both feed it. A single consumer, rather than a
 * write per event launched on its own, keeps two events for one order from
 * overtaking each other while one waits on the database, so the change log
 * is in the order the changes were heard.
 *
 * A change the ledger has not seen before, that moves an order forward and is
 * [announce]d, goes to [onNewState]: the caller decides whether that is news.
 * A write that fails is reported with the order; the next resync puts the
 * ledger right, since the orders API still has the order's current state.
 */
class OrderRecorder(
    private val store: OrderStore,
    private val clock: InstantSource,
    private val onNewState: (Order, OrderSource) -> Unit,
    private val onError: (Exception, Order) -> Unit,
) {
    private class Incoming(
        val order: Order,
        val event: String?,
        val source: OrderSource,
        val announce: Boolean,
    )

    private val incoming = Channel<Incoming>(Channel.UNLIMITED)

    // Written by the one consumer, read by the metrics scrape on another thread.
    @Volatile private var newStates = 0

    @Volatile private var stale = 0

    @Volatile private var repeats = 0

    @Volatile private var failures = 0

    /** Never blocks and never fails: the socket's lane must not wait on the database. */
    fun offer(
        order: Order,
        event: String?,
        source: OrderSource,
        /** False for a change found by a resync that nobody needs telling about again. */
        announce: Boolean = true,
    ) {
        incoming.trySend(Incoming(order, event, source, announce))
    }

    fun start(scope: CoroutineScope): Job = scope.launch { for (item in incoming) record(item) }

    fun stats() = OrderRecorderStats(newStates, stale, repeats, failures)

    @Suppress("TooGenericExceptionCaught") // A failed write is reported with its order; the next resync repairs it.
    private suspend fun record(item: Incoming) {
        try {
            when (store.recordOrder(item.order, item.event, item.source, clock.instant())) {
                Recorded.NEW_STATE -> {
                    newStates += 1
                    if (item.announce) onNewState(item.order, item.source)
                }

                Recorded.STALE -> {
                    stale += 1
                }

                Recorded.REPEAT -> {
                    repeats += 1
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failures += 1
            onError(failure, item.order)
        }
    }
}
