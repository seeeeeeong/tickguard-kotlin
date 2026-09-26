package tickguard.runner

import kotlinx.coroutines.delay
import tickguard.orders.Order
import tickguard.orders.OrderStore
import tickguard.stream.Decimal
import java.time.InstantSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Waits until the ledger shows each of [orderIds] filled, as the order
 * stream records them, and returns what they brought net of fees; null once
 * one is refused or cancelled, or when [wait] runs out with one still open.
 * Suspends rather than blocks, so the engine goes on recording the very
 * events it waits for.
 */
internal suspend fun awaitFilled(
    orders: OrderStore,
    clock: InstantSource,
    orderIds: List<String>,
    wait: Duration = FILL_WAIT,
    poll: Duration = FILL_POLL,
): Decimal? {
    val deadline = clock.instant().plus(wait.toJavaDuration())
    var filled: List<Order>? = null
    var failed = false
    while (filled == null && !failed && clock.instant().isBefore(deadline)) {
        val known = orderIds.mapNotNull { orders.order(it) }
        if (known.size == orderIds.size && known.all { it.status == "FILLED" }) filled = known
        failed = known.any { it.closed && it.status != "FILLED" }
        if (filled == null && !failed) delay(poll)
    }
    return filled?.fold(Decimal.ZERO) { sum, order -> sum + proceeds(order) }
}

/** What a filled sale brought: its amount less commission and tax. */
private fun proceeds(order: Order): Decimal {
    val execution = order.execution
    val amount = execution.filledAmount ?: (execution.filledQuantity * (execution.averageFilledPrice ?: Decimal.ZERO))
    return amount - (execution.commission ?: Decimal.ZERO) - (execution.tax ?: Decimal.ZERO)
}

/** A market order in the session fills in seconds; a minute without a fill will not fund a buy in time. */
private val FILL_WAIT = 60.seconds

/** How often the ledger is read while waiting. */
private val FILL_POLL = 2.seconds
