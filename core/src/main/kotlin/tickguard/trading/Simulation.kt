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
    /** Every time a risk limit changed what the strategy asked for. */
    val riskNotes: List<RiskNote> = emptyList(),
) {
    val performance: Performance get() = performance(equity)
}

/**
 * Runs [strategy] over daily [bars] and returns what the portfolio was worth
 * each day.
 *
 * Each day, in the order it could happen: trades decided the evening before
 * fill at this day's open, sells first so their cash is there for the buys;
 * the day closes and the portfolio is marked; the risk engine looks at the
 * close; then the strategy sees history up to it and proposes for tomorrow,
 * through the risk engine. A symbol with no bar on a day, a holiday on its
 * market, keeps its pending trade until it next opens.
 */
fun simulate(
    bars: Map<String, List<Bar>>,
    strategy: Strategy,
    costs: CostModel,
    cash: Decimal,
    risk: RiskEngine? = null,
): SimulationResult =
    Run(
        bars.mapValues { (_, list) ->
            list.sortedBy { it.day }
        },
        costs,
        Portfolio(cash),
        risk,
    ).over(strategy)

/** Weights clamped to [0, 1] and scaled down to sum to at most 1: there is no borrowing and no shorting. */
internal fun scaled(targets: Map<String, Decimal>): Map<String, Decimal> {
    val clamped =
        targets.mapValues { (_, w) ->
            if (w < Decimal.ZERO) {
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

/** One simulation's state, so each step reads as the day it models. */
private class Run(
    private val series: Map<String, List<Bar>>,
    private val costs: CostModel,
    private val portfolio: Portfolio,
    private val risk: RiskEngine?,
) {
    private val byDay = series.mapValues { (_, list) -> list.associateBy { it.day } }
    private val visible = series.keys.associateWith { 0 }.toMutableMap()
    private val lastClose = LinkedHashMap<String, Decimal>()
    private val fills = ArrayList<Fill>()

    fun over(strategy: Strategy): SimulationResult {
        val days = series.values.flatMap { list -> list.map { it.day } }.toSortedSet()
        val equity = ArrayList<DayValue>(days.size)
        var pending: Map<String, Decimal> = emptyMap()

        for (day in days) {
            val today = byDay.mapNotNull { (code, calendar) -> calendar[day]?.let { code to it } }.toMap()
            pending = rebalance(day, today, pending)

            for ((code, bar) in today) {
                visible[code] = visible.getValue(code) + 1
                lastClose[code] = bar.close
            }
            val value = portfolio.value(lastClose)
            equity += DayValue(day, value)
            risk?.observe(day, value)
            if (risk?.state == TradingState.HALTED) pending = emptyMap()

            val proposed = scaled(strategy.targets(History(day, series, visible.toMap())))
            val reviewed = risk?.review(day, proposed, portfolio.holdings().keys) ?: proposed
            pending = pending + reviewed
        }
        return SimulationResult(strategy.name, equity, fills, portfolio.feesPaid, risk?.notes().orEmpty())
    }

    /** Trades toward [pending] at today's opens. Returns what could not trade today. */
    private fun rebalance(
        day: LocalDate,
        today: Map<String, Bar>,
        pending: Map<String, Decimal>,
    ): Map<String, Decimal> {
        if (pending.isEmpty()) return pending
        val worth = portfolio.value(lastClose + today.mapValues { it.value.open })
        val tradable = pending.filterKeys { it in today }
        val wanted = tradable.mapValues { (code, weight) -> (worth * weight / today.getValue(code).open).floor() }

        for ((code, target) in wanted) {
            val excess = portfolio.quantity(code) - target
            if (excess > 0) trade(day, code, Side.SELL, excess, today.getValue(code).open)
        }
        if (risk?.allowsBuys() == false) return pending - tradable.keys
        for ((code, target) in wanted) {
            val open = today.getValue(code).open
            val price = costs.fillPrice(Side.BUY, open)
            val quantity = minOf(target - portfolio.quantity(code), affordable(price))
            val capped = risk?.capBuy(day, code, quantity, price) ?: quantity
            if (capped > 0) trade(day, code, Side.BUY, capped, open)
        }
        return pending - tradable.keys
    }

    private fun affordable(price: Decimal): Long {
        val perShare = price * (Decimal.ONE + costs.commission)
        // Division rounds at the twentieth place, so a quotient a hair under a
        // whole number can round up to it; the multiplication back is exact.
        val shares = (portfolio.cash / perShare).floor()
        return if (perShare * Decimal.of(shares) > portfolio.cash) shares - 1 else shares
    }

    private fun trade(
        day: LocalDate,
        code: String,
        side: Side,
        quantity: Long,
        open: Decimal,
    ) {
        val price = costs.fillPrice(side, open)
        val fee = costs.fees(side, price * Decimal.of(quantity))
        when (side) {
            Side.BUY -> portfolio.buy(code, quantity, price, fee)
            Side.SELL -> portfolio.sell(code, quantity, price, fee)
        }
        fills += Fill(day, code, side, quantity, price, fee)
    }
}
