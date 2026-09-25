package tickguard.trading

import tickguard.stream.Decimal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.MonthDay

/** One sleeve's monthly proposal: where it stands, what its strategy wants, and the trades between. */
data class SleeveProposal(
    val sleeve: Sleeve,
    val position: SleevePosition,
    /** Cash plus holdings at the last close, in the price currency. */
    val value: Decimal,
    val targets: Map<String, Decimal>,
    val trades: List<ProposedTrade>,
    /** Set when the sleeve is past its loss limit, and the trades already reflect it. */
    val stopped: LossAction?,
    /** The close the proposal was priced at. */
    val asOf: LocalDate,
)

/**
 * The trades that bring [sleeve] back to its strategy's targets, from what it
 * holds now and what that is worth at the last close. Sized on the sleeve's
 * current value, not its starting capital: a sleeve that grew rebalances what
 * it has.
 *
 * Past the loss limit, a sleeve that stops buying proposes only its sells,
 * and a sleeve that stops for good proposes nothing; the caller alerts, and
 * the person switches it off.
 */
fun proposeRebalance(
    sleeve: Sleeve,
    position: SleevePosition,
    bars: Map<String, List<Bar>>,
): SleeveProposal? {
    val series = bars.filterKeys { it in sleeve.universe }.filterValues { it.isNotEmpty() }
    if (series.isEmpty()) return null
    val asOf = series.values.maxOf { list -> list.maxOf { it.day } }
    val prices = series.mapValues { (_, list) -> list.maxBy { it.day }.close }
    val value =
        position.holdings.entries.fold(position.cash) { total, (code, quantity) ->
            total + quantity * (prices[code] ?: Decimal.ZERO)
        }
    val targets = sleeve.strategy().targets(History.of(asOf, series))
    val proposed = proposeTrades(targets, position.holdings, prices, value, sleeve.band)
    val breached = value < sleeve.capital * (Decimal.ONE - sleeve.lossLimit)
    val stopped = if (breached) sleeve.lossAction else null
    val trades =
        when (stopped) {
            null -> proposed
            LossAction.STOP_BUYING -> proposed.filter { it.side == Side.SELL }
            LossAction.STOP_SLEEVE -> emptyList()
        }
    return SleeveProposal(sleeve, position, value, targets, trades, stopped, asOf)
}

/** New Year's Day: the one first-weekday-of-the-month the US market is always shut. */
private val NEW_YEAR = MonthDay.of(1, 1)

/**
 * The first weekday of the month, Seoul time, when the month's rebalance is
 * proposed and later placed. A market holiday on that day is caught again
 * where orders are placed, against the exchange calendar.
 */
fun isRebalanceDay(date: LocalDate): Boolean {
    val weekday = date.dayOfWeek != DayOfWeek.SATURDAY && date.dayOfWeek != DayOfWeek.SUNDAY
    if (!weekday || MonthDay.from(date) == NEW_YEAR) return false
    val earlier = (1 until date.dayOfMonth).map { date.withDayOfMonth(it) }
    return earlier.none { day ->
        day.dayOfWeek != DayOfWeek.SATURDAY && day.dayOfWeek != DayOfWeek.SUNDAY && MonthDay.from(day) != NEW_YEAR
    }
}
