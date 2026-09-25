package tickguard.trading

import java.time.LocalDate

/**
 * Daily bars kept for backtests. Written by upsert: adjusted prices are
 * rewritten back through history after a split or a dividend, so a later
 * fetch of the same day is the truer one.
 */
interface BarStore {
    suspend fun recordBars(bars: List<Bar>)

    /** One symbol's bars with `from <= day <= to`, oldest first. */
    suspend fun bars(
        code: String,
        from: LocalDate,
        to: LocalDate,
    ): List<Bar>
}
