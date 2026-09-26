package tickguard.runner

import kotlinx.coroutines.delay
import tickguard.orders.OrderStore
import java.time.InstantSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Waits until the ledger shows each of [orderIds] filled, as the order
 * stream records them; false once one is refused or cancelled, or when
 * [wait] runs out with one still open. Suspends rather than blocks, so the
 * engine goes on recording the very events it waits for.
 */
internal suspend fun awaitFilled(
    orders: OrderStore,
    clock: InstantSource,
    orderIds: List<String>,
    wait: Duration = FILL_WAIT,
    poll: Duration = FILL_POLL,
): Boolean {
    val deadline = clock.instant().plus(wait.toJavaDuration())
    var outcome: Boolean? = null
    while (outcome == null && clock.instant().isBefore(deadline)) {
        val known = orderIds.mapNotNull { orders.order(it) }
        outcome =
            when {
                known.size == orderIds.size && known.all { it.status == "FILLED" } -> true
                known.any { it.closed && it.status != "FILLED" } -> false
                else -> null
            }
        if (outcome == null) delay(poll)
    }
    return outcome ?: false
}

/** A market order in the session fills in seconds; a minute without a fill will not fund a buy in time. */
private val FILL_WAIT = 60.seconds

/** How often the ledger is read while waiting. */
private val FILL_POLL = 2.seconds
