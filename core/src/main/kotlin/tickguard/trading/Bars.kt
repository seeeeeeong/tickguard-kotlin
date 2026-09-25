package tickguard.trading

import tickguard.stream.Decimal
import java.time.LocalDate

/** One day of one symbol, as the candles API returns it (adjusted for splits and dividends by default). */
data class Bar(
    val code: String,
    val day: LocalDate,
    val open: Decimal,
    val high: Decimal,
    val low: Decimal,
    val close: Decimal,
    val volume: Decimal,
)

/**
 * The bars a strategy may look at on [day]: that day's close and everything
 * before it, nothing after.
 *
 * This is where look-ahead is ruled out, by construction rather than by
 * review. A strategy is handed this view, not the series, and the view is cut
 * at the decision day. The trades it asks for fill at the next day's open, so
 * even the close it decides on is a price it could not have traded.
 */
class History internal constructor(
    val day: LocalDate,
    private val series: Map<String, List<Bar>>,
    /** Per symbol, how many bars of its series are on or before [day]. */
    private val visible: Map<String, Int>,
) {
    val codes: Set<String> get() = series.keys

    /** The last [count] bars of [code] up to [day], oldest first; fewer if the history is shorter. */
    fun bars(
        code: String,
        count: Int,
    ): List<Bar> {
        val bars = series[code] ?: return emptyList()
        val end = visible[code] ?: 0
        return bars.subList(maxOf(0, end - count), end)
    }

    /** The last [count] closes of [code], or null if there are not that many yet. */
    fun closes(
        code: String,
        count: Int,
    ): List<Decimal>? = bars(code, count).takeIf { it.size == count }?.map { it.close }

    companion object {
        /** A view on [day] of whole series that may run past it: each is cut at [day]. */
        fun of(
            day: LocalDate,
            series: Map<String, List<Bar>>,
        ): History {
            val sorted = series.mapValues { (_, bars) -> bars.sortedBy { it.day } }
            return History(day, sorted, sorted.mapValues { (_, bars) -> bars.count { !it.day.isAfter(day) } })
        }
    }

    /** Whether [code] traded on [day] itself: no bar that day, no decision on today's close. */
    fun tradedToday(code: String): Boolean = bars(code, 1).lastOrNull()?.day == day
}
