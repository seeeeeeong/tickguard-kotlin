package tickguard.trading

import tickguard.stream.Decimal

/**
 * Holds, in equal parts, every symbol whose close is above its moving
 * average, and cash for the rest: the long-standing trend filter. It trades
 * only when a symbol crosses its average, which keeps costs low, and it steps
 * aside in long declines, which is where holding loses most.
 */
class TrendFilter(
    private val days: Int = DEFAULT_TREND_DAYS,
) : Strategy {
    override val name = "trend-sma$days"

    override fun targets(history: History): Map<String, Decimal> {
        val above =
            history.codes.associateWith { code ->
                val closes = history.closes(code, days) ?: return@associateWith null
                closes.last() > average(closes)
            }
        // A symbol without enough history yet is left as it is, not sold.
        val known = above.filterValues { it != null }
        val holding = known.filterValues { it == true }.keys
        if (holding.isEmpty()) return known.mapValues { Decimal.ZERO }
        val weight = Decimal.ONE / Decimal.of(holding.size.toLong())
        return known.mapValues { (code, _) -> if (code in holding) weight else Decimal.ZERO }
    }
}

/**
 * Every [every] trading days, holds the [top] symbols that rose most over
 * [lookback] days, in equal parts, and none that fell: relative momentum
 * picks the leaders, absolute momentum keeps a falling market in cash.
 */
class MomentumRotation(
    private val lookback: Int = DEFAULT_LOOKBACK,
    private val top: Int = DEFAULT_TOP,
    private val every: Int = DEFAULT_REBALANCE_DAYS,
) : Strategy {
    override val name = "momentum-${lookback}d-top$top"

    private var daysSince = every

    override fun targets(history: History): Map<String, Decimal> {
        daysSince += 1
        val returns =
            history.codes.mapNotNull { code ->
                val closes = history.closes(code, lookback + 1) ?: return@mapNotNull null
                code to closes.last() / closes.first() - Decimal.ONE
            }
        // Waits for the rebalance day, and for enough history to rank on.
        if (daysSince < every || returns.isEmpty()) return emptyMap()
        daysSince = 0
        val leaders =
            returns
                .filter { it.second > Decimal.ZERO }
                .sortedByDescending { it.second }
                .take(
                    top,
                ).map { it.first }
        val weight = if (leaders.isEmpty()) Decimal.ZERO else Decimal.ONE / Decimal.of(leaders.size.toLong())
        return history.codes.associateWith { if (it in leaders) weight else Decimal.ZERO }
    }
}

/** The ten-month average the trend filter is usually quoted with, in trading days. */
private const val DEFAULT_TREND_DAYS = 200

/** Six months of trading days: the middle of the horizons momentum is documented over. */
private const val DEFAULT_LOOKBACK = 126

/** Two leaders: concentrated enough to follow momentum, not a bet on one name. */
private const val DEFAULT_TOP = 2

/** About a month of trading days: rotation often enough to follow leaders, rarely enough to keep costs down. */
private const val DEFAULT_REBALANCE_DAYS = 21

private fun average(values: List<Decimal>): Decimal =
    values.fold(Decimal.ZERO, Decimal::plus) / Decimal.of(values.size.toLong())
