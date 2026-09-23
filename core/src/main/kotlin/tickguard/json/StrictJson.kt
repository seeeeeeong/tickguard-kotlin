package tickguard.json

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * JSON parsed the way the original's `JSON.parse` parsed it.
 *
 * kotlinx.serialization accepts an unquoted token anywhere a value may appear —
 * `<html>` parses to a primitive whose content is `<html>` — and only complains
 * when something asks it for a number. `JSON.parse` rejects the whole text. The
 * difference is observable: a frame with a garbled value was an unknown frame in
 * the original and would become a decode failure here. So every unquoted token
 * is checked against what JSON allows, and a text with any other is not JSON.
 */
object StrictJson {
    /** The number grammar from RFC 8259: no leading zeros, no bare dot, no plus sign. */
    private val NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

    /** Unquoted tokens JSON allows besides numbers. */
    private val KEYWORDS = setOf("true", "false", "null")

    /** Null when [text] is not JSON, exactly where `JSON.parse` would throw. */
    fun parse(text: String): JsonElement? {
        val parsed =
            try {
                Json.parseToJsonElement(text)
            } catch (_: SerializationException) {
                return null
            }
        return parsed.takeIf { isValid(it) }
    }

    private fun isValid(element: JsonElement): Boolean =
        when (element) {
            is JsonObject -> element.values.all(::isValid)
            is JsonArray -> element.all(::isValid)
            is JsonPrimitive -> element.isString || element.content in KEYWORDS || NUMBER.matches(element.content)
        }
}
