package tickguard.backtest

import tickguard.rules.Position
import tickguard.rules.drawdownFromAverage
import tickguard.store.TickRow
import tickguard.stream.Decimal
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

// The Kotlin side of the parity harness. tools/parity/replay-golden.ts runs
// the original's replay over the same ticks and writes the same lines; the
// two outputs must match byte for byte, decimals to the twentieth place
// included.

/** A spread of thresholds, from one that fires often to one that fires rarely. Same as the golden's. */
private val RAPID_MOVE_PERCENTS = listOf("0.5", "1", "2", "3")

/** See [RAPID_MOVE_PERCENTS]. */
private val DRAWDOWN_PERCENTS = listOf("1", "2", "3", "5")

/** The window the rapid-move cases watch. Same as the golden's. */
private val PARITY_SPAN = 5.minutes

/** The hold the drawdown cases need, the live default. Same as the golden's. */
private val PARITY_HOLD = 2.minutes

/**
 * The cases the golden was made with. [holdSkew] lengthens every drawdown
 * hold without renaming its case: the mutation check that proves the
 * comparison can fail.
 */
fun parityCases(holdSkew: Duration = Duration.ZERO): List<BacktestCase> =
    RAPID_MOVE_PERCENTS.map { rapidMoveCase(it, PARITY_SPAN) } +
        DRAWDOWN_PERCENTS.map { percent ->
            val case = drawdownCase(percent, PARITY_HOLD)
            if (holdSkew == Duration.ZERO) {
                case
            } else {
                val fraction = (Decimal.parse(percent, "percent") / Decimal.HUNDRED).toPlainString()
                BacktestCase(case.name, drawdownFromAverage(fraction, PARITY_HOLD + holdSkew), case.direction)
            }
        }

/**
 * Every fire of every case, one line each, symbol by symbol in the order the
 * ticks list them. Averages are not recorded historically, so each symbol's
 * first price stands in, on both sides.
 */
fun parityLines(
    ticks: List<TickRow>,
    cases: List<BacktestCase> = parityCases(),
): String =
    buildString {
        for ((code, rows) in ticks.groupBy { it.code }) {
            val positions = mapOf(code to Position(code, Decimal.parse(rows.first().price, "average"), Decimal.ZERO))
            for (case in cases) {
                for (fire in replay(case, rows, positions)) append(fireLine(fire)).append('\n')
            }
        }
    }

private fun fireLine(fire: Fire): String =
    (
        listOf(
            fire.case,
            fire.code,
            "${fire.at.toEpochMilli()}",
            fire.direction.wire,
            fire.price.toPlainString(),
            fire.title,
        ) +
            DEFAULT_HORIZONS.map { fire.after[it]?.toPlainString() ?: "-" }
    ).joinToString("\t")
