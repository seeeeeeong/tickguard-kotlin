package tickguard.rules

import tickguard.stream.Decimal
import tickguard.stream.Trade
import java.time.Instant
import kotlin.time.Duration

/**
 * Rules are Kotlin functions, not a DSL.
 *
 * A DSL would need a parser, a grammar and its own error messages, and would
 * buy nothing: there is one author, the conditions are arithmetic over a
 * handful of fields, and a plain function keeps type checking and unit tests
 * that a string expression throws away.
 */
data class Position(
    val code: String,
    /** Weighted average purchase price. */
    val averagePrice: Decimal,
    val quantity: Decimal,
)

class RuleContext(
    val trade: Trade,
    /** Injected so evaluation is testable without waiting, and replayable on market time. */
    val now: Instant,
    /** Absent when the symbol is watched but not held. */
    val position: Position?,
    /**
     * Recent history for this symbol. Null before anything is retained.
     * A snapshot can cover less time than asked for, so a rule that needs a
     * full span must check `span` rather than trust the request.
     */
    val window: (Duration) -> WindowSnapshot?,
)

data class Signal(
    val ruleId: String,
    val code: String,
    val title: String,
    val detail: String,
    val firedAt: Instant,
    /**
     * The cooldown this fire started, for signals raised by the rule engine.
     * Delivery reports back against it: a fire that reached nobody must not
     * keep its rule quiet.
     */
    val cooldownKey: String? = null,
)

/** What a rule says when its condition holds. The engine adds the rule and the time. */
data class Outcome(
    val code: String,
    val title: String,
    val detail: String,
)

interface Rule {
    val id: String

    /**
     * How long the condition must hold continuously before it fires.
     *
     * Prometheus calls this `for`, and lists it among the four decisions to get
     * right on every alert rule. It is not the same thing as a cooldown: a
     * cooldown stops an alert repeating after it fires, while this stops a blip
     * from becoming an alert at all.
     *
     * A price that touches -7.01% for one print and bounces is the spread
     * moving, not a position worth waking up for. Zero means fire on the first
     * evaluation that holds, which suits a rule whose condition is already a
     * span — `rapidMove` asks about five minutes, so requiring it to persist
     * would be asking twice.
     */
    val holdFor: Duration

    /**
     * How long the same key stays quiet after firing.
     *
     * Without it a threshold crossing fires on every tick while the price sits
     * on the wrong side of it — hundreds of identical alerts for one event.
     */
    val cooldown: Duration

    /**
     * Distinguishes occurrences that should be reported separately. Usually the
     * symbol; a rule that watches a level would include the level.
     */
    fun dedupeKey(context: RuleContext): String

    /** Null means the condition did not hold. */
    fun evaluate(context: RuleContext): Outcome?
}
