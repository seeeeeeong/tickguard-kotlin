package tickguard.verdict

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import tickguard.text.jsTrim

enum class Direction(
    val wire: String,
) {
    UP("up"),
    DOWN("down"),
    NEUTRAL("neutral"),
    ;

    companion object {
        fun fromWire(wire: String): Direction? = entries.firstOrNull { it.wire == wire }
    }
}

/** What a model is asked about a headline, and what it must answer. */
data class Verdict(
    /** False for filler: fund-position filings, price predictions, listicles. */
    val relevant: Boolean,
    val direction: Direction,
    /** 0 is no effect on the price, 1 a major event. */
    val impact: Double,
    /** One short Korean sentence: what happened. */
    val summary: String,
)

class VerdictFormatError(
    message: String,
) : IllegalArgumentException(message)

/** A model in JSON mode returns JSON, not necessarily this JSON. */
private const val MAX_SUMMARY = 200

/**
 * The answer is validated field by field rather than trusted. An unchecked
 * impact of "high" or 7 would reach an alert threshold comparison as garbage.
 */
@Suppress("ThrowsCount") // One check per field, in the original's order, each with its own message.
fun parseVerdict(value: JsonElement?): Verdict {
    val fields = value as? JsonObject ?: throw VerdictFormatError("verdict is not an object")

    val relevant = (fields["relevant"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
    val directionText = (fields["direction"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val direction = directionText?.let(Direction::fromWire)
    val impact = (fields["impact"] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
    val summary = (fields["summary"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.jsTrim()

    if (relevant == null) throw VerdictFormatError("relevant is not a boolean")
    if (direction == null) {
        throw VerdictFormatError("direction ${quoted(fields["direction"])} is not up, down or neutral")
    }
    val validImpact =
        impact?.takeIf { it.isFinite() && it in 0.0..1.0 }
            ?: throw VerdictFormatError("impact ${quoted(fields["impact"])} is not a number from 0 to 1")
    if (summary.isNullOrEmpty()) throw VerdictFormatError("summary is empty")
    return Verdict(relevant, direction, validImpact, summary.take(MAX_SUMMARY))
}

/** As `JSON.stringify` put a value in the message: its JSON, or `undefined` when absent. */
private fun quoted(value: JsonElement?): String = value?.toString() ?: "undefined"

/** What the judge is told about a story: public facts only. */
data class JudgeInput(
    val code: String,
    val title: String,
    val publisher: String,
    val source: String,
)

interface Judge {
    val model: String

    suspend fun judge(item: JudgeInput): Verdict
}
