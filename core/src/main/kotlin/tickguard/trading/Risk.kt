package tickguard.trading

import tickguard.stream.Decimal
import java.time.LocalDate

/** What the portfolio may do. The risk engine moves between these; a strategy cannot. */
enum class TradingState {
    ACTIVE,

    /** Sells only, for the day after a close that lost more than the daily limit. */
    REDUCING,

    /** Nothing, for good: the drawdown limit was crossed. Only a person restarts it. */
    HALTED,
}

/**
 * Hard limits a strategy cannot argue with. Each is off when left at its
 * default, so a backtest states which ones it ran under.
 */
data class RiskLimits(
    /** The largest share of the portfolio one symbol may be. */
    val maxWeight: Decimal = Decimal.ONE,
    val maxPositions: Int = Int.MAX_VALUE,
    /** The most one buy may spend. */
    val maxOrderValue: Decimal? = null,
    /** A close this fraction below the previous one stops buying for the next day. */
    val dailyLoss: Decimal? = null,
    /** A close this fraction below the peak stops all trading. */
    val maxDrawdown: Decimal? = null,
    /** Symbols that may be bought at all; null allows any. */
    val allowed: Set<String>? = null,
)

/** Every time a limit changed what a strategy asked for, and why. */
data class RiskNote(
    val day: LocalDate,
    val code: String?,
    val reason: String,
)

/**
 * Sits between a strategy and the broker. The strategy proposes weights; this
 * clamps them, and at each close decides whether tomorrow may buy, only sell,
 * or do nothing. Deterministic and outside the strategy, so an LLM-driven or
 * overfitted strategy is held to the same limits as any other.
 */
class RiskEngine(
    private val limits: RiskLimits,
) {
    var state: TradingState = TradingState.ACTIVE
        private set

    private var peak: Decimal? = null
    private var previousClose: Decimal? = null
    private val notes = ArrayList<RiskNote>()

    fun notes(): List<RiskNote> = notes.toList()

    /** At each close: updates the peak and the state tomorrow trades under. */
    fun observe(
        day: LocalDate,
        equity: Decimal,
    ) {
        if (state == TradingState.HALTED) return
        val high = peak?.let { if (equity > it) equity else it } ?: equity
        peak = high
        val drawdown = limits.maxDrawdown
        val daily = limits.dailyLoss
        val before = previousClose
        previousClose = equity
        state =
            when {
                drawdown != null && equity < high * (Decimal.ONE - drawdown) -> {
                    notes += RiskNote(day, null, "drawdown past ${drawdown.pct()} of the peak: trading halted")
                    TradingState.HALTED
                }

                daily != null && before != null && equity < before * (Decimal.ONE - daily) -> {
                    notes += RiskNote(day, null, "lost more than ${daily.pct()} today: no buys tomorrow")
                    TradingState.REDUCING
                }

                else -> {
                    TradingState.ACTIVE
                }
            }
    }

    /** A strategy's targets, within the limits. [held] is what the portfolio holds now. */
    fun review(
        day: LocalDate,
        targets: Map<String, Decimal>,
        held: Set<String>,
    ): Map<String, Decimal> {
        if (state == TradingState.HALTED) {
            if (targets.isNotEmpty()) notes += RiskNote(day, null, "halted: ${targets.size} targets ignored")
            return emptyMap()
        }
        val allowed = LinkedHashMap<String, Decimal>()
        for ((code, weight) in targets) {
            if (limits.allowed != null && code !in limits.allowed) {
                notes += RiskNote(day, code, "not on the allowlist")
                continue
            }
            allowed[code] =
                if (weight > limits.maxWeight) {
                    notes += RiskNote(day, code, "weight ${weight.pct()} capped at ${limits.maxWeight.pct()}")
                    limits.maxWeight
                } else {
                    weight
                }
        }
        return withinPositions(day, allowed, held)
    }

    /** New positions past the limit are dropped, in the order the strategy listed them. */
    private fun withinPositions(
        day: LocalDate,
        targets: Map<String, Decimal>,
        held: Set<String>,
    ): Map<String, Decimal> {
        val staying = held.count { code -> targets[code]?.let { it > Decimal.ZERO } ?: true }
        var room = limits.maxPositions - staying
        return targets.filter { (code, weight) ->
            val entering = weight > Decimal.ZERO && code !in held
            when {
                !entering -> {
                    true
                }

                room > 0 -> {
                    room -= 1
                    true
                }

                else -> {
                    notes += RiskNote(day, code, "already at ${limits.maxPositions} positions")
                    false
                }
            }
        }
    }

    fun allowsBuys(): Boolean = state == TradingState.ACTIVE

    /** A buy of [quantity] at [price], cut to the order value limit. */
    fun capBuy(
        day: LocalDate,
        code: String,
        quantity: Long,
        price: Decimal,
    ): Long {
        val cap = limits.maxOrderValue ?: return quantity
        val most = (cap / price).floor()
        if (quantity <= most) return quantity
        notes += RiskNote(day, code, "buy of $quantity cut to $most by the ${cap.toPlainString()} order limit")
        return most
    }
}

private fun Decimal.pct(): String = "${(this * Decimal.HUNDRED).format(1)}%"
