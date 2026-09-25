package tickguard.trading

import tickguard.stream.Decimal

/** One suggested trade, for a person to place. */
data class ProposedTrade(
    val code: String,
    val side: Side,
    /** Shares, fractional: Toss trades US shares in fractions. */
    val quantity: Decimal,
    /** What the trade is worth at [price], in the price's currency. */
    val value: Decimal,
    val price: Decimal,
    /** The weight the symbol is moving from and to. */
    val from: Decimal,
    val to: Decimal,
)

/** Fractional shares are quoted to six places, as Toss's own order events carry them (0.015632). */
private const val QUANTITY_PLACES = 6

/**
 * The trades that move a sleeve from what it holds to [targets], skipping any
 * symbol already within [band] of its target: a drift of a point or two is
 * not worth a trade's cost (Vanguard's 5% threshold rebalancing).
 *
 * [capital] is the sleeve's value in the price currency. Holdings count at
 * [prices]; a target symbol without a price is left out rather than guessed.
 * Sells come first, so a person placing them in order has the cash for buys.
 */
fun proposeTrades(
    targets: Map<String, Decimal>,
    held: Map<String, Decimal>,
    prices: Map<String, Decimal>,
    capital: Decimal,
    band: Decimal,
): List<ProposedTrade> {
    val codes = (targets.keys + held.filterValues { it.signum() > 0 }.keys).filter { it in prices }
    return codes
        .mapNotNull { code ->
            val price = prices.getValue(code)
            trade(
                code,
                price,
                (held[code] ?: Decimal.ZERO) * price / capital,
                targets[code] ?: Decimal.ZERO,
                capital,
                band,
            )
        }.sortedBy { if (it.side == Side.SELL) 0 else 1 }
}

/** The trade from weight [current] to [target], or null when it is too small to be worth making. */
@Suppress("LongParameterList") // One symbol's position, target and the sleeve's scale.
private fun trade(
    code: String,
    price: Decimal,
    current: Decimal,
    target: Decimal,
    capital: Decimal,
    band: Decimal,
): ProposedTrade? {
    val gap = target - current
    // A symbol leaving the sleeve is sold out however small; one staying is left inside the band.
    val insideBand = gap.abs() < band && target.signum() != 0
    val value = (gap * capital).abs()
    val quantity = Decimal.parse((value / price).format(QUANTITY_PLACES), "quantity")
    if (insideBand || gap.signum() == 0 || quantity.signum() == 0) return null
    return ProposedTrade(code, if (gap.signum() > 0) Side.BUY else Side.SELL, quantity, value, price, current, target)
}
