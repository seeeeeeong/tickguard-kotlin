package tickguard.trading

import tickguard.stream.Decimal

enum class Side { BUY, SELL }

/**
 * What a trade costs beyond its price, each as a fraction of the trade's value.
 *
 * No defaults on purpose. Commission, tax and slippage differ by market and
 * change with regulation and promotions, and a backtest with a made-up number
 * here reports a made-up result. The values come from configuration, and the
 * ledger's real fills (their `commission` and `tax` fields) are what to set
 * them from.
 */
data class CostModel(
    /** Charged on both sides. */
    val commission: Decimal,
    /** Charged on sells only: Korea's securities transaction tax, the SEC fee in the US. */
    val sellTax: Decimal,
    /** How far the fill lands from the reference price, against the trader. */
    val slippage: Decimal,
) {
    /** The price actually paid or received for a reference price. */
    fun fillPrice(
        side: Side,
        reference: Decimal,
    ): Decimal =
        when (side) {
            Side.BUY -> reference * (Decimal.ONE + slippage)
            Side.SELL -> reference * (Decimal.ONE - slippage)
        }

    /** Commission, and tax on a sell, for a trade worth [value]. */
    fun fees(
        side: Side,
        value: Decimal,
    ): Decimal =
        when (side) {
            Side.BUY -> value * commission
            Side.SELL -> value * (commission + sellTax)
        }
}
