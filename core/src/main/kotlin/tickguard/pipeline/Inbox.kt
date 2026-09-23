package tickguard.pipeline

import kotlinx.coroutines.channels.Channel
import tickguard.stream.channelOf
import tickguard.stream.parseTopic
import java.time.InstantSource

/**
 * Buffers frames between the socket and whatever evaluates them.
 *
 * The receive path must never do work. Toss closes the connection if the order
 * channel stalls for two seconds, and events from the gap are never redelivered
 * — so a slow rule evaluation does not merely delay an alert, it loses order
 * events permanently.
 *
 * Which means saturation has to be survivable, and the two channel families
 * survive it differently:
 *
 * - Quotes are LOSSY by design and carry no sequence number, so dropping the
 *   oldest is aligned with how the server already behaves. Latest state wins.
 * - Order events are LOSSLESS and irreplaceable. They are never dropped. If
 *   that buffer ever fills, the right answer is to fail loudly rather than
 *   quietly discard the one thing that cannot be re-fetched.
 *
 * [offer] runs on the socket's reader thread and [take] on the engine, so the
 * queues are guarded by one lock that is held only to move items, never while
 * anything is evaluated.
 */
class Inbox<T>(
    /** Oldest quotes are discarded past this. Sized for a burst, not a backlog. */
    private val quoteCapacity: Int = DEFAULT_QUOTE_CAPACITY,
    /** Orders are never dropped; this only bounds memory before failing loudly. */
    private val orderCapacity: Int = DEFAULT_ORDER_CAPACITY,
    private val onOverflow: (Lane, Int) -> Unit = { _, _ -> },
    private val onOrderBacklogFull: (Int) -> Unit = {},
    private val clock: InstantSource = InstantSource.system(),
) {
    private val lock = Any()
    private val queues = Lane.entries.associateWith { ArrayDeque<Queued<T>>() }
    private val accepted = Lane.entries.associateWith { 0 }.toMutableMap()
    private val maxQueued = Lane.entries.associateWith { 0 }.toMutableMap()
    private var droppedQuotes = 0

    /** Conflated: however many offers land, a waiting consumer wakes once and drains them all. */
    private val work = Channel<Unit>(Channel.CONFLATED)

    /** Returns false only when an order could not be accepted, which is fatal. */
    fun offer(
        topic: String,
        item: T,
    ): Boolean {
        val lane = laneOf(topic)
        val queued = Queued(item, clock.millis())
        var dropped = 0
        var refusedAt: Int? = null

        synchronized(lock) {
            val queue = queues.getValue(lane)
            if (lane == Lane.ORDERS && queue.size >= orderCapacity) {
                refusedAt = queue.size
            } else {
                queue.addLast(queued)
                if (lane == Lane.QUOTES && queue.size > quoteCapacity) {
                    dropped = queue.size - quoteCapacity
                    repeat(dropped) { queue.removeFirst() }
                    droppedQuotes += dropped
                }
                accepted[lane] = accepted.getValue(lane) + 1
                maxQueued[lane] = maxOf(maxQueued.getValue(lane), queue.size)
            }
        }

        refusedAt?.let {
            onOrderBacklogFull(it)
            return false
        }
        if (dropped > 0) onOverflow(Lane.QUOTES, dropped)
        work.trySend(Unit)
        return true
    }

    /** Orders first: they are the ones with a deadline attached. */
    fun take(max: Int): List<Queued<T>> =
        synchronized(lock) {
            val batch = ArrayList<Queued<T>>(minOf(max, queues.values.sumOf { it.size }))
            for (lane in listOf(Lane.ORDERS, Lane.QUOTES)) {
                val queue = queues.getValue(lane)
                while (batch.size < max && queue.isNotEmpty()) batch += queue.removeFirst()
            }
            batch
        }

    /** Suspends until something has been offered since the last wake. */
    suspend fun awaitWork() = work.receive()

    fun size(lane: Lane? = null): Int =
        synchronized(lock) { if (lane == null) queues.values.sumOf { it.size } else queues.getValue(lane).size }

    fun stats(): InboxStats =
        synchronized(lock) {
            InboxStats(
                queued = queues.mapValues { it.value.size },
                accepted = accepted.toMap(),
                droppedQuotes = droppedQuotes,
                maxQueued = maxQueued.toMap(),
            )
        }

    private companion object {
        /** A burst of this many quotes fits; beyond it the oldest are stale anyway. */
        const val DEFAULT_QUOTE_CAPACITY = 10_000

        /** Far past any real backlog of order events; reaching it means consumption has stopped. */
        const val DEFAULT_ORDER_CAPACITY = 10_000
    }
}

enum class Lane { QUOTES, ORDERS }

/** An item and when it arrived, so the time it waited can be measured. */
data class Queued<T>(
    val item: T,
    val at: Long,
)

data class InboxStats(
    val queued: Map<Lane, Int>,
    val accepted: Map<Lane, Int>,
    val droppedQuotes: Int,
    val maxQueued: Map<Lane, Int>,
)

fun laneOf(topic: String): Lane {
    val parsed = parseTopic(topic) ?: return Lane.QUOTES
    return if (channelOf(parsed.type) == "personal") Lane.ORDERS else Lane.QUOTES
}
