package tickguard.gateway

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import tickguard.json.StrictJson

/**
 * Server frames arrive as untrusted JSON, so every field is checked before it
 * becomes a typed value. Shapes follow the AsyncAPI spec, which is the source
 * of truth: https://openapi.tossinvest.com/openapi-docs/latest/asyncapi.json
 *
 * An unrecognised frame is returned as [ServerFrame.Unknown] rather than thrown.
 * Toss can add frame types without warning, and a socket carrying live quotes
 * must not die because one frame was not understood.
 */
sealed interface ServerFrame {
    data class Subscriptions(
        /** Echoed back only when the declaration carried one. */
        val id: String?,
        val subscribed: List<String>,
        val rejected: List<RejectedTopic>,
    ) : ServerFrame

    data class Message(
        /** Full key: `trade:us:AAPL`, `personal:order:3`. */
        val topic: String,
        /** Left undecoded: each channel owns the shape of its own payload. */
        val data: JsonElement?,
    ) : ServerFrame

    data class Error(
        /**
         * `wrong-format` · `no-type` · `invalid-type` · `no-codes` · `too-many-topics`
         * · `too-many` · `rate-limit-exceeded` · `internal-error` · `server-shutdown`
         */
        val code: String,
        val message: String,
        val id: String?,
    ) : ServerFrame

    data object Pong : ServerFrame

    /** Not a protocol error. Carries the raw text so it can be logged and ignored. */
    data class Unknown(
        val raw: String,
    ) : ServerFrame
}

data class RejectedTopic(
    /** The full key that failed, e.g. `trade:us:NOPE`. */
    val target: String,
    /** `stock-not-found` · `symbol-market-mismatch` · `account-not-found` */
    val code: String,
    val message: String,
)

/** `server-shutdown` arrives just before the server closes the socket. */
const val SERVER_SHUTDOWN = "server-shutdown"

fun parseFrame(raw: String): ServerFrame {
    val frame = StrictJson.parse(raw) as? JsonObject ?: return ServerFrame.Unknown(raw)

    return when (frame.string("type")) {
        "subscriptions" -> parseSubscriptions(frame, raw)
        "message" -> parseMessage(frame, raw)
        "error" -> parseError(frame, raw)
        "pong" -> ServerFrame.Pong
        else -> ServerFrame.Unknown(raw)
    }
}

private fun parseSubscriptions(
    frame: JsonObject,
    raw: String,
): ServerFrame {
    val subscribed = (frame["subscribed"] as? JsonArray)?.map { it.asString() }
    val rejected = frame["rejected"] as? JsonArray
    if (subscribed == null || rejected == null || null in subscribed) return ServerFrame.Unknown(raw)

    return ServerFrame.Subscriptions(
        id = frame.string("id"),
        subscribed = subscribed.filterNotNull(),
        rejected = rejected.mapNotNull(::toRejectedTopic),
    )
}

private fun parseMessage(
    frame: JsonObject,
    raw: String,
): ServerFrame {
    val topic = frame.string("topic")
    if (topic.isNullOrEmpty()) return ServerFrame.Unknown(raw)
    return ServerFrame.Message(topic, frame["data"])
}

private fun parseError(
    frame: JsonObject,
    raw: String,
): ServerFrame {
    val error = frame["error"] as? JsonObject
    val code = error?.string("code")
    val message = error?.string("message")
    if (code == null || message == null) return ServerFrame.Unknown(raw)
    return ServerFrame.Error(code, message, frame.string("id"))
}

private fun toRejectedTopic(value: JsonElement): RejectedTopic? {
    val entry = value as? JsonObject
    val target = entry?.string("target")
    val code = entry?.string("code")
    val message = entry?.string("message")
    return if (target != null && code != null && message != null) RejectedTopic(target, code, message) else null
}

private fun JsonElement.asString(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.string(key: String): String? = this[key]?.asString()
