package tickguard.trading

import tickguard.orders.Order
import tickguard.stream.Decimal
import java.time.Instant

/** What a sleeve may do with money. Set per sleeve by the user; `LIVE` is never a default. */
enum class SleeveMode { OFF, DRY_RUN, LIVE }

/** What crossing a sleeve's loss limit does. */
enum class LossAction {
    /** No more buys; sells still happen. */
    STOP_BUYING,

    /** Nothing more, for good. */
    STOP_SLEEVE,
}

/**
 * A slice of the account run by one strategy, with its own capital, symbols
 * and limits, accounted for on its own: a result that is the sum of three
 * strategies says nothing about any of them.
 */
data class Sleeve(
    val id: String,
    val name: String,
    /** In the price currency (USD), fixed at the start: what the sleeve is measured against. */
    val capital: Decimal,
    val universe: List<String>,
    val strategy: () -> Strategy,
    /** A drift inside this is not worth a trade. */
    val band: Decimal,
    /** A fall of this fraction from the capital triggers [lossAction]. */
    val lossLimit: Decimal,
    val lossAction: LossAction,
    /** Set for a sleeve that trades dips every weekday instead of rebalancing monthly to [strategy]. */
    val dip: DipRules? = null,
)

/** What a sleeve holds, and what it has left to spend, from its attributed fills. */
data class SleevePosition(
    val holdings: Map<String, Decimal>,
    val cash: Decimal,
    val feesPaid: Decimal,
    val fills: Int,
)

/**
 * Which sleeve each of the account's orders belongs to.
 *
 * An order the execution module placed was recorded with its sleeve, and
 * [tagged] says so. Otherwise a symbol that belongs to exactly one sleeve,
 * and that the account did not already hold before the test ([personal]),
 * can only have been that sleeve's. A symbol the account also holds for
 * itself is never guessed: untagged, it is a personal trade.
 * Orders placed before [since] predate the test and belong to nobody.
 * A dip sleeve claims only tagged orders: it trades only through the
 * executor, which tags every order, and its symbols overlap the others', so
 * counting it as an owner would take untagged orders from the sleeve that
 * placed them.
 */
fun attribute(
    orders: List<Order>,
    sleeves: List<Sleeve>,
    tagged: Map<String, String>,
    personal: Set<String>,
    since: Instant,
): Map<String, List<Order>> {
    val owners =
        sleeves
            .filter { it.dip == null }
            .flatMap { s -> s.universe.map { it to s.id } }
            .groupBy({ it.first }, { it.second })
    return orders
        .filter { !it.orderedAt.isBefore(since) }
        .mapNotNull { order ->
            val sleeve =
                tagged[order.orderId]
                    ?: owners[order.symbol]?.singleOrNull()?.takeIf { order.symbol !in personal }
            sleeve?.let { it to order }
        }.groupBy({ it.first }, { it.second })
}

/** A sleeve's holdings and cash from its orders' fills: what was bought, less what was sold. */
fun position(
    sleeve: Sleeve,
    orders: List<Order>,
): SleevePosition {
    val holdings = LinkedHashMap<String, Decimal>()
    var cash = sleeve.capital
    var fees = Decimal.ZERO
    var fills = 0
    for (order in orders.sortedBy { it.orderedAt }) {
        val filled = order.execution.filledQuantity
        if (filled.signum() == 0) continue
        val amount = order.execution.filledAmount ?: (filled * (order.execution.averageFilledPrice ?: Decimal.ZERO))
        val fee = (order.execution.commission ?: Decimal.ZERO) + (order.execution.tax ?: Decimal.ZERO)
        val held = holdings[order.symbol] ?: Decimal.ZERO
        when (order.side) {
            "BUY" -> {
                holdings[order.symbol] = held + filled
                cash = cash - amount - fee
            }

            "SELL" -> {
                holdings[order.symbol] = held - filled
                cash = cash + amount - fee
            }
        }
        fees += fee
        fills += 1
    }
    return SleevePosition(holdings.filterValues { it.signum() != 0 }, cash, fees, fills)
}
