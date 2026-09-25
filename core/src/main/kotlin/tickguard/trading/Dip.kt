package tickguard.trading

import tickguard.orders.Order
import tickguard.stream.Decimal

/**
 * The dip sleeve's rules, as backtested on 2026-09-25 over 2016-2026 and not
 * tuned since: a large cap that fell a tenth from its month's high while
 * still above its 200-day average and oversold is bought in two tranches,
 * sold at a gain of [takeProfit] or a loss of [stopLoss], and money not in a
 * dip waits in [parking]. On the 2015 large caps, which include the ones
 * that later fell, it roughly matched holding the S&P 500; the parking is
 * what kept it there, since dips alone leave most of the money idle.
 */
data class DipRules(
    val drop: Decimal = Decimal.parse("0.10", "drop"),
    val lookback: Int = 20,
    val rsiDays: Int = 14,
    val oversold: Decimal = Decimal.parse("30", "oversold"),
    val trendDays: Int = 200,
    /** A second tranche once the price is this far under the first fill. */
    val addDrop: Decimal = Decimal.parse("0.08", "add drop"),
    val takeProfit: Decimal = Decimal.parse("0.08", "take profit"),
    val stopLoss: Decimal = Decimal.parse("0.20", "stop loss"),
    /** Positions held at once; each takes up to a slot's worth in two tranches. */
    val slots: Int = 3,
    val parking: String = "SPY",
    /** Cash under this is left as cash: a smaller parking order costs more than it earns. */
    val minPark: Decimal = Decimal.parse("10", "min park"),
    /** Kept back from every buy, so a fill a little above the close cannot overdraw the sleeve. */
    val buffer: Decimal = Decimal.parse("2", "buffer"),
)

/** One open position: what was paid, and where it started. */
data class Lot(
    val quantity: Decimal,
    /** Dollars paid for [quantity], fees included. */
    val cost: Decimal,
    val firstPrice: Decimal,
    val buys: Int,
) {
    val average: Decimal get() = cost / quantity
}

/**
 * The sleeve's open positions from its orders' fills: a buy opens or adds to
 * a lot, a sell takes out its share of the cost, and a lot sold out is gone,
 * so the next buy of that symbol starts a new one.
 */
fun lots(orders: List<Order>): Map<String, Lot> {
    val open = LinkedHashMap<String, Lot>()
    orders
        .sortedBy { it.orderedAt }
        .filter { it.execution.filledQuantity.signum() != 0 && it.execution.averageFilledPrice != null }
        .forEach { apply(open, it) }
    return open
}

/** One filled order's effect on its symbol's lot. */
private fun apply(
    open: MutableMap<String, Lot>,
    order: Order,
) {
    val filled = order.execution.filledQuantity
    val price = order.execution.averageFilledPrice ?: return
    val fee = (order.execution.commission ?: Decimal.ZERO) + (order.execution.tax ?: Decimal.ZERO)
    val amount = order.execution.filledAmount ?: (filled * price)
    val lot = open[order.symbol]
    when {
        order.side == "BUY" -> {
            open[order.symbol] =
                lot?.copy(quantity = lot.quantity + filled, cost = lot.cost + amount + fee, buys = lot.buys + 1)
                    ?: Lot(filled, amount + fee, price, 1)
        }

        order.side == "SELL" && lot != null -> {
            val left = lot.quantity - filled
            if (left.signum() <= 0) {
                open.remove(order.symbol)
            } else {
                open[order.symbol] = lot.copy(quantity = left, cost = lot.cost * left / lot.quantity)
            }
        }
    }
}

/**
 * The dip sleeve's trades for the next session, from the closes up to the
 * last one: take profits and stops first, then a second tranche where one
 * is due, then new positions in free slots, the most oversold first. Buys
 * are paid from cash, and from the parked fund when cash is short; what is
 * left over is parked.
 *
 * A position being sold still holds its slot today: its money arrives with
 * the fill, and the next proposal spends it. Past the sleeve's loss limit it
 * only sells, and leaves its cash as cash.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod", "CognitiveComplexMethod") // The day's decision, in a trader's order.
fun proposeDip(
    sleeve: Sleeve,
    position: SleevePosition,
    open: Map<String, Lot>,
    bars: Map<String, List<Bar>>,
    rules: DipRules,
): SleeveProposal? {
    val series =
        bars.filterKeys { it in sleeve.universe }.filterValues { it.isNotEmpty() }.mapValues { (_, list) ->
            list.sortedBy { it.day }
        }
    val park = series[rules.parking] ?: return null
    val asOf = series.values.maxOf { it.last().day }
    val prices = series.mapValues { (_, list) -> list.last().close }
    val value =
        position.holdings.entries.fold(position.cash) { total, (code, quantity) ->
            total + quantity * (prices[code] ?: Decimal.ZERO)
        }
    val buying = value >= sleeve.capital * (Decimal.ONE - sleeve.lossLimit)
    val tranche = sleeve.capital / Decimal.of(rules.slots.toLong() * 2)
    val sells = ArrayList<ProposedTrade>()
    val buys = ArrayList<ProposedTrade>()

    val held = open.filterKeys { it != rules.parking && (position.holdings[it]?.signum() ?: 0) > 0 }
    for ((code, lot) in held) {
        val price = prices[code] ?: continue
        val quantity = position.holdings.getValue(code)
        when {
            price >= lot.average * (Decimal.ONE + rules.takeProfit) ||
                price <= lot.average * (Decimal.ONE - rules.stopLoss) -> {
                sells += sell(code, quantity, price)
            }

            buying && lot.buys < 2 && price <= lot.firstPrice * (Decimal.ONE - rules.addDrop) -> {
                buys +=
                    buy(code, tranche, price)
            }
        }
    }
    if (buying) {
        val free = rules.slots - held.size
        series
            .filterKeys { it != rules.parking && it !in held }
            .mapNotNull { (code, list) -> signal(list, rules)?.let { code to it } }
            .sortedBy { it.second }
            .take(maxOf(free, 0))
            .forEach { (code, _) -> buys += buy(code, tranche, prices.getValue(code)) }
    }

    val parkPrice = park.last().close
    val parked = position.holdings[rules.parking] ?: Decimal.ZERO
    val spendable = position.cash - rules.buffer
    val needed = buys.fold(Decimal.ZERO) { sum, trade -> sum + trade.value }
    when {
        needed > spendable -> {
            // Keep the buys cash and all of the parking could pay for, then sell only the parking they need,
            // a little more than the shortfall since the fill is at tomorrow's price, not today's close.
            val kept = affordable(buys, spendable + parked * parkPrice / PARKING_MARGIN)
            buys.retainAll(kept)
            val shortfall =
                (kept.fold(Decimal.ZERO) { sum, trade -> sum + trade.value } - spendable) * PARKING_MARGIN / parkPrice
            if (shortfall.signum() > 0 && parked.signum() > 0) {
                sells += sell(rules.parking, if (shortfall > parked) parked else shortfall, parkPrice)
            }
        }

        buying && spendable - needed >= rules.minPark -> {
            buys += buy(rules.parking, spendable - needed, parkPrice)
        }
    }
    return SleeveProposal(
        sleeve,
        position,
        value,
        emptyMap(),
        sells + buys,
        if (buying) null else sleeve.lossAction,
        asOf,
    )
}

/** How much deeper than the close a parking sale goes, so tomorrow's price still covers the buys. */
private val PARKING_MARGIN = Decimal.parse("1.03", "parking margin")

/** The buys cash covers, in order: the most urgent first, as proposed. */
private fun affordable(
    buys: List<ProposedTrade>,
    cash: Decimal,
): List<ProposedTrade> {
    var left = cash
    return buys.filter { trade -> (trade.value <= left).also { if (it) left -= trade.value } }
}

/**
 * How oversold [bars] closed, if they closed as a dip: above the trend, a
 * tenth or more under the lookback's high, and under the oversold line.
 * Lower is deeper. Null when it is not a dip or the history is too short.
 */
internal fun signal(
    bars: List<Bar>,
    rules: DipRules,
): Decimal? {
    if (bars.size < rules.trendDays + 1) return null
    val close = bars.last().close
    val trend =
        bars.takeLast(rules.trendDays).fold(Decimal.ZERO) { sum, bar -> sum + bar.close } /
            Decimal.of(rules.trendDays.toLong())
    val high = bars.takeLast(rules.lookback).maxOf { it.high }
    val strength = rsi(bars.map { it.close }, rules.rsiDays) ?: return null
    return strength.takeIf { close > trend && close <= high * (Decimal.ONE - rules.drop) && it < rules.oversold }
}

/**
 * Wilder's relative strength over [days], smoothed from the first change on,
 * as the backtest computed it (an exponential mean with weight 1/[days]).
 */
internal fun rsi(
    closes: List<Decimal>,
    days: Int,
): Decimal? {
    if (closes.size < days + 1) return null
    val weight = Decimal.ONE / Decimal.of(days.toLong())
    val changes = closes.zipWithNext { before, after -> after - before }
    var up = gain(changes.first())
    var down = gain(-changes.first())
    for (change in changes.drop(1)) {
        up += (gain(change) - up) * weight
        down += (gain(-change) - down) * weight
    }
    return if (down.signum() == 0) Decimal.HUNDRED else Decimal.HUNDRED - Decimal.HUNDRED / (Decimal.ONE + up / down)
}

private fun gain(change: Decimal) = if (change.signum() > 0) change else Decimal.ZERO

private fun buy(
    code: String,
    dollars: Decimal,
    price: Decimal,
) = ProposedTrade(code, Side.BUY, (dollars / price).down(SHARE_PLACES), dollars, price, Decimal.ZERO, Decimal.ZERO)

private fun sell(
    code: String,
    quantity: Decimal,
    price: Decimal,
) = ProposedTrade(code, Side.SELL, quantity.down(SHARE_PLACES), quantity * price, price, Decimal.ZERO, Decimal.ZERO)

/** Fractional shares to six places, as Toss quotes them. */
private const val SHARE_PLACES = 6
