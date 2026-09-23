package tickguard.stream

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import tickguard.gateway.ServerFrame
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Decoding lives here rather than in the frame parser because each channel
 * owns the shape of its own payload. The gateway hands over undecoded JSON on
 * purpose.
 *
 * A payload that will not decode is returned as a reason, not thrown. One odd
 * frame must not take down a stream carrying thousands of ticks a minute, and
 * the count of what was dropped is worth more than a stack trace.
 */
data class Trade(
    val type: String,
    val code: String,
    val price: Decimal,
    val volume: Decimal,
    val at: Instant,
    val currency: String,
)

data class DecodeFailure(
    val topic: String,
    val reason: String,
)

sealed interface DecodeResult {
    data class Ok(
        val trade: Trade,
    ) : DecodeResult

    data class Failed(
        val failure: DecodeFailure,
    ) : DecodeResult
}

fun decodeTrade(frame: ServerFrame.Message): DecodeResult {
    val parsed = parseTopic(frame.topic) ?: return fail(frame.topic, "topic has no code segment")
    val data = frame.data as? JsonObject ?: return fail(frame.topic, "payload is not an object")

    val price = data.string("price") ?: return fail(frame.topic, "price is not a string")
    val volume = data.string("volume") ?: return fail(frame.topic, "volume is not a string")
    val timestamp = data.string("timestamp") ?: return fail(frame.topic, "timestamp is not a string")
    val at = parseTimestamp(timestamp) ?: return fail(frame.topic, "unparsable timestamp $timestamp")

    return try {
        DecodeResult.Ok(
            Trade(
                type = parsed.type,
                code = parsed.code,
                price = Decimal.parse(price, "price"),
                volume = Decimal.parse(volume, "volume"),
                at = at,
                // KR quotes omit currency; the market is implied by the channel type.
                currency = data.string("currency") ?: "KRW",
            ),
        )
    } catch (notANumber: NotANumberError) {
        fail(frame.topic, notANumber.message.orEmpty())
    }
}

/**
 * Toss stamps trades as ISO-8601 with an offset (`2026-09-22T23:07:43.000+09:00`),
 * the form the original's `new Date()` read. A stamp without an offset would be
 * local time there and is refused here, rather than guessing a zone.
 */
private fun parseTimestamp(text: String): Instant? =
    try {
        OffsetDateTime.parse(text).toInstant()
    } catch (_: DateTimeParseException) {
        null
    }

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun fail(
    topic: String,
    reason: String,
) = DecodeResult.Failed(DecodeFailure(topic, reason))
