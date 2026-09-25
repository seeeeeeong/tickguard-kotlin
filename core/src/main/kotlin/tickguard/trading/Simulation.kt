package tickguard.trading

import tickguard.stream.Decimal
import java.time.LocalDate

data class DayValue(
    val day: LocalDate,
    val equity: Decimal,
)

data class Fill(
    val day: LocalDate,
    val code: String,
    val side: Side,
    val quantity: Long,
    val price: Decimal,
    val fee: Decimal,
)

class SimulationResult(
    val strategy: String,
    val equity: List<DayValue>,
    val fills: List<Fill>,
    val feesPaid: Decimal,
) {
    val performance: Performance get() = performance(equity)
}

/**
 * Runs [strategy] over daily [bars] and returns what the portfolio was worth
 * each day.
 *
 * Each day, in the order it could happen: trades decided the evening before
 * fill at this day's open, sells first so their cash is there for the buys;
 * the day closes and the portfolio is marked; then the strategy sees history
 * up to this close and decides for tomorrow. A symbol with no bar on a day,
 * a holiday on its market, keeps its pending trade until it next opens.
 */
fun simulate(
    bars: Map<String, List<Bar>>,
    strategy: Strategy,
    costs: CostModel,
    cash: Decimal,
): SimulationResult {
    val series = bars.mapValues { (_, list) -> list.sortedBy { it.day } }
    val byDay = series.mapValues { (_, list) -> list.associateBy { it.day } }
    val days = series.values.flatMap { list -> list.map { it.day } }.toSortedSet()

    val portfolio = Portfolio(cash)
    val visible = series.keys.associateWith { 0 }.toMutableMap()
    val lastClose = LinkedHashMap<String, Decimal>()
    val equity = ArrayList<DayValue>(days.size)
    val fills = ArrayList<Fill>()
    var pending: Map<String, Decimal> = emptyMap()

    for (day in days) {
        val today = byDay.mapNotNull { (code, calendar) -> calendar[day]?.let { code to it } }.toMap()
        pending = rebalance(day, today, pending, portfolio, costs, lastClose, fills)

        for ((code, bar) in today) {
            visible[code] = visible.getValue(code) + 1
            lastClose[code] = bar.close
        }
        equity += DayValue(day, portfolio.value(lastClose))

        pending = pending + scaled(strategy.targets(History(day, series, visible.toMap())))
    }
    return SimulationResult(strategy.name, equity, fills, portfolio.feesPaid)
}

/** Weights clamped to [0, 1] and scaled down to sum to at most 1: there is no borrowing and no shorting. */
internal fun scaled(targets: Map<String, Decimal>): Map<String, Decimal> {
    val clamped =
        targets.mapValues { (_, w) ->
            if (w <
                Decimal.ZERO
            ) {
                Decimal.ZERO
            } else if (w > Decimal.ONE) {
                Decimal.ONE
            } else {
                w
            }
        }
    val total = clamped.values.fold(Decimal.ZERO, Decimal::plus)
    return if (total <= Decimal.ONE) clamped else clamped.mapValues { (_, w) -> w / total }
}

/** Trades toward [pending] at today's opens. Returns what could not trade today. */
@Suppress("LongParameterList") // One day's state, passed rather than held, so the loop above reads top to bottom.
private fun rebalance(
    day: LocalDate,
    today: Map<String, Bar>,
    pending: Map<String, Decimal>,
    portfolio: Portfolio,
    costs: CostModel,
    lastClose: Map<String, Decimal>,
    fills: MutableList<Fill>,
): Map<String, Decimal> {
    if (pending.isEmpty()) return pending
    val opens = lastClose + today.mapValues { it.value.open }
    val worth = portfolio.value(opens)
    val tradable = pending.filterKeys { it in today }
    val wanted = tradable.mapValues { (code, weight) -> (worth * weight / today.getValue(code).open).floor() }

    for ((code, target) in wanted) {
        val excess = portfolio.quantity(code) - target
        if (excess > 0) fills += trade(day, code, Side.SELL, excess, today.getValue(code).open, portfolio, costs)
    }
    for ((code, target) in wanted) {
        val open = today.getValue(code).open
        val price = costs.fillPrice(Side.BUY, open)
        val perShare = price * (Decimal.ONE + costs.commission)
        // Division rounds at the twentieth place, so a quotient a hair under a
        // whole number can round up to it; the multiplication back is exact.
        val affordable =
            (portfolio.cash / perShare).floor().let {
                if (perShare * Decimal.of(it) >
                    portfolio.cash
                ) {
                    it - 1
                } else {
                    it
                }
            }
        val quantity = minOf(target - portfolio.quantity(code), affordable)
        if (quantity > 0) fills += trade(day, code, Side.BUY, quantity, open, portfolio, costs)
    }
    return pending - tradable.keys
}

@Suppress("LongParameterList") // A fill's facts, all of them needed to book it.
private fun trade(
    day: LocalDate,
    code: String,
    side: Side,
    quantity: Long,
    open: Decimal,
    portfolio: Portfolio,
    costs: CostModel,
): Fill {
    val price = costs.fillPrice(side, open)
    val fee = costs.fees(side, price * Decimal.of(quantity))
    when (side) {
        Side.BUY -> portfolio.buy(code, quantity, price, fee)
        Side.SELL -> portfolio.sell(code, quantity, price, fee)
    }
    return Fill(day, code, side, quantity, price, fee)
}
