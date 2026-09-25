package tickguard.rules

import tickguard.stream.Decimal
import tickguard.stream.changeRatio
import tickguard.time.kstTime
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/*
 * The rules worth having on day one. Each one exists because the Toss app
 * cannot express it: its alerts fire on an absolute price, and none of these
 * are absolute.
 *
 * Every title and detail is built as the original built it, character for
 * character, since alert text is part of what parity means.
 */

/**
 * Fires when a held position falls a given fraction below what it cost.
 *
 * The threshold moves with the average price, so it cannot be set once in an
 * app and left. A one hour cooldown because a position that is down stays
 * down: the useful alert is the first crossing, not the next four hundred
 * ticks on the same side of it.
 */
fun drawdownFromAverage(
    fraction: String,
    // Two minutes: a single print through the threshold is the spread moving.
    holdFor: Duration = 2.minutes,
    cooldown: Duration = 1.hours,
): Rule {
    val remaining = Decimal.ONE - Decimal.parse(fraction, "fraction")
    val percent = (Decimal.parse(fraction, "fraction") * Decimal.HUNDRED).toPlainString()

    return rule("drawdown-${percent}pct", holdFor, cooldown) { context ->
        val position =
            context.position ?: run {
                context.decline(DeclineReason.NO_POSITION)
                return@rule null
            }
        val trade = context.trade

        val threshold = position.averagePrice * remaining
        if (trade.price >= threshold) return@rule null

        val moved = changeRatio(position.averagePrice, trade.price) * Decimal.HUNDRED
        Outcome(
            code = trade.code,
            title = "${trade.code} 평단 대비 ${moved.format(1)}%",
            detail = "현재 ${trade.price} · 평단 ${position.averagePrice} · 보유 ${position.quantity}",
        )
    }
}

/**
 * Fires when a single print is large relative to a fixed size.
 *
 * A crude proxy for unusual activity until the window aggregates land — it
 * reads one tick rather than a rate, so it catches block prints and misses
 * a thousand small ones. Deliberately short cooldown: repeated large prints
 * are the signal, so a long silence would hide it.
 */
fun largePrint(
    minimumVolume: String,
    // A single print is the event. Requiring it to persist would mean
    // requiring a second one, which is a different rule.
    holdFor: Duration = Duration.ZERO,
    cooldown: Duration = 10.minutes,
): Rule {
    val threshold = Decimal.parse(minimumVolume, "minimumVolume")

    return rule("large-print-$minimumVolume", holdFor, cooldown) { context ->
        val trade = context.trade
        if (trade.volume < threshold) return@rule null

        Outcome(
            code = trade.code,
            title = "${trade.code} 대량 체결 ${trade.volume}주",
            detail = "${trade.price} ${trade.currency} · 체결 ${kstTime(trade.at)}",
        )
    }
}

/**
 * Fires when price moves by a fraction within a span.
 *
 * Declines rather than guessing when the retained history does not cover the
 * span: a window trimmed by the count bound would otherwise answer a five
 * minute question with thirty seconds of data and look like a sharp move.
 *
 * Cooldown is the window itself. Shorter would re-report the same move as it
 * keeps qualifying tick after tick.
 */
fun rapidMove(
    fraction: String,
    window: Duration,
    cooldown: Duration = window,
): Rule {
    val magnitude = Decimal.parse(fraction, "fraction")
    val percent = (magnitude * Decimal.HUNDRED).toPlainString()
    val windowMs = window.inWholeMilliseconds

    // The condition is already a span, so requiring it to persist would be
    // asking the same question twice.
    return rule("rapid-move-${percent}pct-${windowMs}ms", Duration.ZERO, cooldown) { context ->
        val recent = context.window(window)
        val spanMs = recent?.span?.inWholeMilliseconds ?: 0
        // At least two prints, and half the span: the least that makes the answer meaningful.
        if (recent == null || recent.count < 2 || spanMs * 2 < windowMs) {
            context.decline(DeclineReason.INSUFFICIENT_SPAN)
            return@rule null
        }

        val moved = changeRatio(recent.open, recent.last)
        if (moved.abs() < magnitude) return@rule null

        val direction = if (moved.signum() > 0) "상승" else "하락"
        val seconds = (spanMs / MILLIS_PER_SECOND).roundToLong()
        Outcome(
            code = context.trade.code,
            title = "${context.trade.code} ${seconds}초간 ${(moved * Decimal.HUNDRED).format(1)}% $direction",
            detail = "${recent.open} → ${recent.last} · 고 ${recent.high} 저 ${recent.low} · ${recent.count}틱",
        )
    }
}

/** Seconds in a title are rounded half up, as `Math.round` did. */
private const val MILLIS_PER_SECOND = 1_000.0

/** Every built-in rule is keyed by symbol. */
private fun rule(
    id: String,
    holdFor: Duration,
    cooldown: Duration,
    evaluate: (RuleContext) -> Outcome?,
): Rule =
    object : Rule {
        override val id = id
        override val holdFor = holdFor
        override val cooldown = cooldown

        override fun dedupeKey(context: RuleContext) = context.trade.code

        override fun evaluate(context: RuleContext) = evaluate(context)
    }
