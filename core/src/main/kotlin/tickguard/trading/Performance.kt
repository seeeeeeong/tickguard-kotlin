package tickguard.trading

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * How a run did, in the numbers that compare strategies. Doubles, not
 * [tickguard.stream.Decimal]: these are ratios to read, not money to book.
 */
data class Performance(
    val totalReturn: Double,
    /** Compound annual growth, by calendar days. */
    val cagr: Double,
    /** Deepest fall from a previous high, as a positive fraction. */
    val maxDrawdown: Double,
    /** Mean daily return over its deviation, annualised by trading days. Risk-free rate taken as zero. */
    val sharpe: Double,
    val days: Int,
)

/** Trading days in a year, to annualise a daily Sharpe ratio. */
private const val TRADING_DAYS = 252.0

/** Calendar days in a year, to annualise growth over the span actually covered. */
private const val CALENDAR_DAYS = 365.25

fun performance(equity: List<DayValue>): Performance {
    if (equity.size < 2) return Performance(0.0, 0.0, 0.0, 0.0, equity.size)
    val values = equity.map { it.equity.toDouble() }
    val total = values.last() / values.first() - 1
    val span = equity.last().day.toEpochDay() - equity.first().day.toEpochDay()
    val cagr = if (span > 0) (1 + total).pow(CALENDAR_DAYS / span) - 1 else 0.0

    var peak = values.first()
    var deepest = 0.0
    for (value in values) {
        peak = maxOf(peak, value)
        deepest = maxOf(deepest, 1 - value / peak)
    }

    val returns = values.zipWithNext { a, b -> b / a - 1 }
    val mean = returns.average()
    val deviation = sqrt(returns.sumOf { (it - mean) * (it - mean) } / (returns.size - 1).coerceAtLeast(1))
    val sharpe = if (deviation > 0) mean / deviation * sqrt(TRADING_DAYS) else 0.0

    return Performance(total, cagr, deepest, sharpe, equity.size)
}
