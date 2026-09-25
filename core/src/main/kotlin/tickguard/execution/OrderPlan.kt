package tickguard.execution

import tickguard.stream.Decimal
import tickguard.trading.ProposedTrade
import tickguard.trading.Side
import tickguard.trading.SleeveMode
import tickguard.trading.SleeveProposal
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * An order as it will be sent: a buy by dollar amount, a sell by fractional
 * quantity, both at market, as the API takes US fractional orders.
 */
data class OrderRequest(
    /** The idempotency key: the same key within ten minutes returns the first result, never a second order. */
    val clientOrderId: String,
    val sleeve: String,
    val symbol: String,
    val side: Side,
    /** Dollars, to the cent. Buys only. */
    val amount: Decimal?,
    /** Shares, to six places. Sells only. */
    val quantity: Decimal?,
)

/** Limits no configuration can raise. */
data class ExecutionLimits(
    /** The most all automated buys in one run may spend, in dollars. */
    val maxBuys: Decimal,
    val maxOrdersPerRun: Int,
)

data class SkippedTrade(
    val sleeve: String,
    val symbol: String,
    val reason: String,
)

data class OrderPlan(
    /** For sleeves in LIVE: sent, sells first. */
    val live: List<OrderRequest>,
    /** For sleeves in DRY_RUN: built the same way, never sent. */
    val dryRun: List<OrderRequest>,
    val skipped: List<SkippedTrade>,
)

/** The smallest buy worth sending: below a dollar a fee or a rounding rule can be most of the order. */
private val MIN_BUY = Decimal.parse("1", "min buy")

/** Cents: the API takes an order amount in dollars. */
private const val AMOUNT_PLACES = 2

/** The API's limit for a fractional quantity. */
private const val QUANTITY_PLACES = 6

/** `20261102`: a client order id carries the day, so a key is never reused on a later rebalance. */
private val DAY = DateTimeFormatter.BASIC_ISO_DATE

/**
 * Turns proposals into orders, holding each to the limits a strategy cannot
 * argue with. A trade that fails a check is skipped with its reason, never
 * resized into something the proposal did not say: a skipped order is
 * visible; a quietly altered one is not.
 *
 * Amounts and quantities are rounded down, never up, so an order is never
 * larger than proposed; a sell is capped at what the sleeve holds.
 */
fun planOrders(
    proposals: List<SleeveProposal>,
    modes: Map<String, SleeveMode>,
    limits: ExecutionLimits,
    date: LocalDate,
): OrderPlan {
    val live = ArrayList<OrderRequest>()
    val dryRun = ArrayList<OrderRequest>()
    val skipped = ArrayList<SkippedTrade>()
    var buys = Decimal.ZERO
    val trades =
        proposals.flatMap { p -> p.trades.map { p to it } }.sortedBy {
            if (it.second.side ==
                Side.SELL
            ) {
                0
            } else {
                1
            }
        }

    for ((proposal, trade) in trades) {
        val sleeve = proposal.sleeve
        val mode = modes[sleeve.id] ?: SleeveMode.OFF
        val request = request(proposal, trade, date)
        val reason =
            when {
                mode == SleeveMode.OFF -> "sleeve is off"
                trade.code !in sleeve.universe -> "not in the sleeve's symbols"
                request == null -> "rounds to nothing"
                else -> refusal(request, sleeve.capital, mode == SleeveMode.LIVE, buys, live.size, limits)
            }
        when {
            reason != null || request == null -> {
                skipped +=
                    SkippedTrade(sleeve.id, trade.code, reason ?: "rounds to nothing")
            }

            mode == SleeveMode.LIVE -> {
                live += request
                request.amount?.let { buys += it }
            }

            else -> {
                dryRun += request
            }
        }
    }
    return OrderPlan(live, dryRun, skipped)
}

/** Why [request] must not go out, or null when it may. The run's totals apply to live orders only. */
@Suppress("LongParameterList") // One order against every limit it is held to.
private fun refusal(
    request: OrderRequest,
    capital: Decimal,
    live: Boolean,
    buysSoFar: Decimal,
    ordersSoFar: Int,
    limits: ExecutionLimits,
): String? {
    val amount = request.amount
    return when {
        amount != null && amount < MIN_BUY -> "buy under \$1"
        amount != null && amount > capital -> "buy above the sleeve's capital"
        live && amount != null && buysSoFar + amount > limits.maxBuys -> "over the run's buy limit"
        live && ordersSoFar >= limits.maxOrdersPerRun -> "over the run's order limit"
        else -> null
    }
}

private fun request(
    proposal: SleeveProposal,
    trade: ProposedTrade,
    date: LocalDate,
): OrderRequest? {
    val id = "tg-${DAY.format(date)}-${proposal.sleeve.id}-${trade.side.name.first()}-${trade.code.replace('.', '_')}"
    return when (trade.side) {
        Side.BUY -> {
            val amount = trade.value.down(AMOUNT_PLACES)
            if (amount.signum() == 0) null else OrderRequest(id, proposal.sleeve.id, trade.code, Side.BUY, amount, null)
        }

        Side.SELL -> {
            val held = proposal.position.holdings[trade.code] ?: Decimal.ZERO
            val quantity = (if (trade.quantity > held) held else trade.quantity).down(QUANTITY_PLACES)
            if (quantity.signum() ==
                0
            ) {
                null
            } else {
                OrderRequest(id, proposal.sleeve.id, trade.code, Side.SELL, null, quantity)
            }
        }
    }
}
