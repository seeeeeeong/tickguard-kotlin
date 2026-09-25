package tickguard.trading

import tickguard.stream.Decimal

data class Holding(
    val quantity: Long,
    /** What the shares cost, fees included. */
    val cost: Decimal,
)

/**
 * Cash and whole-share holdings in one currency, with what trading cost.
 *
 * Whole shares because the Korean market trades only those, and a backtest
 * that buys 0.37 of a share where the account could not would flatter itself.
 */
class Portfolio(
    initialCash: Decimal,
) {
    var cash: Decimal = initialCash
        private set

    var feesPaid: Decimal = Decimal.ZERO
        private set

    var realized: Decimal = Decimal.ZERO
        private set

    private val holdings = LinkedHashMap<String, Holding>()

    fun quantity(code: String): Long = holdings[code]?.quantity ?: 0

    fun holdings(): Map<String, Holding> = holdings.toMap()

    fun buy(
        code: String,
        quantity: Long,
        price: Decimal,
        fee: Decimal,
    ) {
        require(quantity > 0) { "buy of $quantity $code" }
        val spent = price * Decimal.of(quantity) + fee
        require(spent <= cash) { "buy of $quantity $code costs $spent with $cash in cash" }
        cash -= spent
        feesPaid += fee
        val held = holdings[code]
        holdings[code] = Holding((held?.quantity ?: 0) + quantity, (held?.cost ?: Decimal.ZERO) + spent)
    }

    fun sell(
        code: String,
        quantity: Long,
        price: Decimal,
        fee: Decimal,
    ) {
        val held = holdings[code]
        require(
            held != null && quantity in 1..held.quantity,
        ) { "sell of $quantity $code holding ${held?.quantity ?: 0}" }
        val received = price * Decimal.of(quantity) - fee
        val costOfSold = held.cost * Decimal.of(quantity) / Decimal.of(held.quantity)
        cash += received
        feesPaid += fee
        realized += received - costOfSold
        val left = held.quantity - quantity
        if (left == 0L) holdings -= code else holdings[code] = Holding(left, held.cost - costOfSold)
    }

    /** Cash plus every holding at [prices]; a holding without a price counts at what it cost. */
    fun value(prices: Map<String, Decimal>): Decimal =
        holdings.entries.fold(cash) { total, (code, held) ->
            total + (prices[code]?.let { it * Decimal.of(held.quantity) } ?: held.cost)
        }
}
