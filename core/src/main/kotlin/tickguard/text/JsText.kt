package tickguard.text

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

/**
 * Whitespace as JavaScript defines it for `\s` and `trim()`.
 *
 * Neither Java's `\s` (ASCII only) nor Kotlin's `trim()` (which also strips the
 * ASCII separator controls) agrees with it, and the difference is observable:
 * a headline's id is a hash of its normalised text, and a headline containing
 * a no-break space must hash as the original hashed it, or a story already
 * stored would be taken for a new one after the cut-over.
 */
private const val JS_WHITESPACE =
    "\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"

/** One or more JavaScript whitespace characters, as `/\s+/`. */
val JS_SPACES = Regex("[$JS_WHITESPACE]+")

/** Contains any JavaScript whitespace, as `/\s/.test()`. */
val JS_SPACE = Regex("[$JS_WHITESPACE]")

/** What `trim()` removes from the start. */
private val LEADING = Regex("^[$JS_WHITESPACE]+")

/** What `trim()` removes from the end: `\\z`, because Java's `$` would stop before a final line break. */
private val TRAILING = Regex("[$JS_WHITESPACE]+\\z")

/** `String.prototype.trim()`. */
fun String.jsTrim(): String = replace(LEADING, "").replace(TRAILING, "")

/**
 * `Number.prototype.toFixed(places)`. It rounds the exact binary value, half
 * away from zero, so `0.725` — stored as 0.72499… — gives `0.72`. Java's
 * `%.2f` rounds the shortest decimal instead and gives `0.73`, which would
 * change the text of an alert the original sent. A negative keeps its sign
 * even when it rounds to zero; negative zero does not.
 */
@Suppress("ForbiddenMethodCall") // The exact binary value is the point: it is what toFixed rounds.
fun Double.toFixed(places: Int): String {
    val digits = BigDecimal(abs(this)).setScale(places, RoundingMode.HALF_UP).toPlainString()
    return if (this < 0) "-$digits" else digits
}
