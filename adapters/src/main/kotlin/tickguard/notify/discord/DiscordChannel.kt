package tickguard.notify.discord

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.coroutines.executeAsync
import tickguard.network.ERROR_BODY_EXCERPT
import tickguard.network.StatusFailure
import tickguard.notify.Channel
import tickguard.notify.DeliveryFailure
import tickguard.notify.Notification
import java.io.IOException
import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Discord refusing a message. Only a rate limit (429) or Discord's own trouble
 * (5xx) can succeed on a retry; a 404 means the webhook was deleted and a 400
 * that the message itself is wrong, and neither changes by asking again.
 */
class DiscordDeliveryError(
    override val status: Int,
    body: String,
    override val retryAfter: Duration? = null,
) : IOException("Discord rejected the message with $status: ${body.take(ERROR_BODY_EXCERPT)}"),
    StatusFailure,
    DeliveryFailure {
    override val retryable get() = status == HTTP_TOO_MANY_REQUESTS || status >= HTTP_SERVER_ERROR
}

/** Discord's rate limit, sent with Retry-After. */
private const val HTTP_TOO_MANY_REQUESTS = 429

/** Discord's own failures start here. */
private const val HTTP_SERVER_ERROR = 500

/**
 * An execute-webhook call. The message is the notification's text, already formatted.
 *
 * Mentions are switched off. The text carries headlines written by strangers,
 * and one containing `@everyone` would otherwise ping everyone on the server.
 */
class DiscordChannel(
    private val webhookUrl: String,
    http: OkHttpClient,
    /** Without this a hung webhook holds a delivery open and the retry that would have worked never runs. */
    timeout: Duration = 5.seconds,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : Channel {
    override val name = "discord"

    private val client = http.newBuilder().callTimeout(timeout.toJavaDuration()).build()

    override suspend fun send(notification: Notification) {
        val body =
            buildJsonObject {
                put("content", fit(notification.text))
                putJsonObject("allowed_mentions") { putJsonArray("parse") {} }
            }.toString()
        val request =
            Request
                .Builder()
                .url(webhookUrl)
                .post(body.toRequestBody(JSON))
                .build()

        client.newCall(request).executeAsync().use { response ->
            if (!response.isSuccessful) {
                throw DiscordDeliveryError(
                    response.code,
                    withContext(io) { response.body.string() },
                    retryAfter(response.header("Retry-After")),
                )
            }
        }
    }

    private companion object {
        /** What Discord's webhooks expect. */
        val JSON = "application/json".toMediaType()
    }
}

/**
 * Discord refuses content over this many characters with a 400, which is not
 * retried, so an oversized group would be lost whole. Cut instead; the console
 * channel keeps the full text.
 */
const val DISCORD_CONTENT_LIMIT = 2_000

/** Appended to a cut message, so a reader knows there was more. */
private const val CUT_MARK = "\n…(잘림: 전체는 로그)"

internal fun fit(text: String): String =
    if (text.length <= DISCORD_CONTENT_LIMIT) text else text.take(DISCORD_CONTENT_LIMIT - CUT_MARK.length) + CUT_MARK

/**
 * Seconds, possibly fractional (`0.35`). Rounded up to a whole millisecond,
 * since retrying a moment early earns another 429.
 */
internal fun retryAfter(header: String?): Duration? {
    val seconds = header?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: return null
    return ceil(seconds * MILLIS_PER_SECOND).toLong().milliseconds
}

/** Unit arithmetic for [retryAfter]. */
private const val MILLIS_PER_SECOND = 1_000.0
