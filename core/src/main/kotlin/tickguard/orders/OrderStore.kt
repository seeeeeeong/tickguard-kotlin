package tickguard.orders

import tickguard.stream.Decimal
import java.time.Instant

/**
 * The ledger of what the account's orders did: each order's latest state, and
 * every change to it in the order it was seen.
 *
 * The same change can arrive twice — over the stream and again from the
 * resync after a reconnect — and an older one can arrive after a newer one.
 * So a change is keyed by order, status and filled quantity, and an order's
 * latest state only moves forward (see [supersedes]).
 */
interface OrderStore {
    suspend fun recordOrder(
        order: Order,
        /** The stream's event name; null from a resync, which has none. */
        event: String?,
        source: OrderSource,
        seenAt: Instant,
    ): Recorded

    suspend fun order(orderId: String): Order?

    /** Orders not yet in a terminal state, oldest first. What a resync checks. */
    suspend fun openOrders(): List<Order>

    /** Every change seen to one order, in the order it was seen. */
    suspend fun orderHistory(orderId: String): List<OrderChange>
}

enum class OrderSource(
    val wire: String,
) {
    STREAM("stream"),
    RESYNC("resync"),
    ;

    companion object {
        fun fromWire(wire: String): OrderSource = entries.first { it.wire == wire }
    }
}

/** What recording a change did. Only [NEW_STATE] is news worth telling anyone. */
enum class Recorded {
    /** The order's state moved forward. */
    NEW_STATE,

    /** A change not seen before, but older than the state already held: kept in the history only. */
    STALE,

    /** Seen before, from the stream or a resync. Nothing written. */
    REPEAT,
}

data class OrderChange(
    val event: String?,
    val status: String,
    val filledQuantity: Decimal,
    val source: OrderSource,
    val seenAt: Instant,
)

/**
 * Whether [next] is a later state of the order than [held]. Fills only grow,
 * so more filled is later; at the same fill a terminal status is later than
 * a working one, and a terminal status is final.
 */
fun supersedes(
    next: Order,
    held: Order?,
): Boolean {
    if (held == null) return true
    val filled = next.execution.filledQuantity.compareTo(held.execution.filledQuantity)
    return when {
        filled != 0 -> filled > 0
        held.closed -> false
        else -> next.status != held.status
    }
}
