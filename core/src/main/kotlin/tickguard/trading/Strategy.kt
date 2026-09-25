package tickguard.trading

import tickguard.stream.Decimal

/**
 * Decides, from what could be known at a day's close, how much of the
 * portfolio each symbol should be. It proposes; it does not trade. Sizing
 * limits, cash and costs are applied after it, where it cannot argue with them.
 */
interface Strategy {
    val name: String

    /**
     * Target weights by symbol, each between 0 and 1 of the portfolio's value.
     * A symbol left out is not changed; one at 0 is sold out. Weights summing
     * above 1 are scaled down, since there is no borrowing.
     */
    fun targets(history: History): Map<String, Decimal>
}

/** Everything in equal parts from the first day, never traded again: the benchmark every strategy must beat. */
class BuyAndHold : Strategy {
    override val name = "buy-and-hold"

    private var bought = false

    override fun targets(history: History): Map<String, Decimal> {
        if (bought) return emptyMap()
        bought = true
        val weight = Decimal.ONE / Decimal.of(history.codes.size.toLong())
        return history.codes.associateWith { weight }
    }
}
