package tickguard.stream

import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Prices and volumes arrive as strings, and that is deliberate on the server's
 * part: a price is a decimal quantity and binary floats cannot hold most of
 * them exactly.
 *
 * It matters because every rule here compares against a threshold derived by
 * arithmetic. `376.35 * 0.93` is `350.00550000000004` as a float but
 * `350.0055` exactly, and a tick priced at `350.0055` then answers the wrong
 * question at the boundary. The failure is silent — both numbers still look
 * right in a log.
 *
 * This reproduces big.js, which the original computed with, digit for digit:
 *
 * - Scale is not kept. `"339.20"` is `339.2`, as big.js holds it. Every value
 *   is normalised, which also makes equality mean equal quantity: a raw
 *   BigDecimal's `equals` says `339.20 != 339.2`.
 * - Addition, subtraction and multiplication are exact. Only division rounds,
 *   to 20 decimal places, half up — big.js's `DP` and the `RM` the original set.
 *   Not a MathContext: that counts significant digits and rounds sums too.
 * - Text is always plain notation; big.js's `toFixed()`, never an exponent.
 */
@Suppress("TooManyFunctions") // The arithmetic a price needs, so no caller reaches for BigDecimal instead.
@JvmInline
value class Decimal private constructor(
    private val value: BigDecimal,
) : Comparable<Decimal> {
    operator fun plus(other: Decimal) = of(value.add(other.value))

    operator fun minus(other: Decimal) = of(value.subtract(other.value))

    operator fun times(other: Decimal) = of(value.multiply(other.value))

    operator fun div(other: Decimal) = of(value.divide(other.value, DIVISION_PLACES, RoundingMode.HALF_UP))

    operator fun unaryMinus() = of(value.negate())

    override fun compareTo(other: Decimal): Int = value.compareTo(other.value)

    fun abs() = of(value.abs())

    fun signum(): Int = value.signum()

    fun isZero(): Boolean = value.signum() == 0

    /** For a metric or a comparison against a plain number, never for arithmetic. */
    fun toDouble(): Double = value.toDouble()

    /**
     * The exact digits, for storage and display. Not an exponent: a fractional
     * volume of `0.0000001` must be stored as a plain decimal any other reader
     * of the table expects, and an alert reading `보유 1e-7` is a number nobody
     * can act on.
     */
    fun toPlainString(): String = value.toPlainString()

    /**
     * Fixed places, half up, as big.js's `toFixed(places)`. That keeps the sign
     * of a negative value that rounds to zero — `-0.04` to one place is `-0.0` —
     * which BigDecimal, having no negative zero, would drop.
     */
    fun format(places: Int): String {
        val text = value.setScale(places, RoundingMode.HALF_UP).toPlainString()
        return if (value.signum() < 0 && !text.startsWith("-")) "-$text" else text
    }

    /** Cut to [places] decimals toward zero: an order sized from this is never larger than asked for. */
    fun down(places: Int) = of(value.setScale(places, RoundingMode.DOWN))

    /** The largest whole number not above this one. Counting steps without a detour through Double. */
    fun floor(): Long = value.setScale(0, RoundingMode.FLOOR).longValueExact()

    override fun toString(): String = toPlainString()

    companion object {
        /** big.js's `Big.DP`: the decimal places a division is rounded to. */
        private const val DIVISION_PLACES = 20

        /** big.js's number grammar. It refuses a leading `+`, whitespace, `Infinity` and hex. */
        private val NUMERIC = Regex("-?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?")

        /** The original wrote these as string literals — `'0'`, `'1'`, `'100'` — at each use. */
        val ZERO = of(BigDecimal.ZERO)

        /** See [ZERO]. */
        val ONE = of(BigDecimal.ONE)

        /** A ratio times this is a percent. See [ZERO]. */
        val HUNDRED = of(BigDecimal.valueOf(100))

        private fun of(value: BigDecimal) = Decimal(value.stripTrailingZeros())

        /**
         * The only way a string becomes a Decimal. Everything downstream can then
         * assume it is holding a real number, which is the point of having a boundary.
         */
        fun parse(
            text: String,
            field: String,
        ): Decimal {
            if (!NUMERIC.matches(text)) throw NotANumberError(field, text)
            return of(text.toBigDecimalOrNull() ?: throw NotANumberError(field, text))
        }

        /** A whole number, for counts and constants written in code. */
        fun of(value: Long) = of(BigDecimal.valueOf(value))
    }
}

class NotANumberError(
    val field: String,
    val value: String,
) : IllegalArgumentException("$field was not a number: ${JsonPrimitive(value)}")

/** `(to - from) / from`, as a ratio. 0.03 is a 3% rise. */
fun changeRatio(
    from: Decimal,
    to: Decimal,
): Decimal {
    if (from.isZero()) throw ArithmeticException("Cannot compute a change ratio from zero")
    return (to - from) / from
}
