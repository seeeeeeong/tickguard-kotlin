package tickguard.notify.slack

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.coroutines.executeAsync
import tickguard.network.ERROR_BODY_EXCERPT
import tickguard.notify.Channel
import tickguard.notify.Notification
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

class SlackDeliveryError(
    val status: Int,
    body: String,
) : IOException("Slack rejected the message with $status: ${body.take(ERROR_BODY_EXCERPT)}")

/** An incoming webhook. The message is the notification's text, already formatted. */
class SlackChannel(
    private val webhookUrl: String,
    http: OkHttpClient,
    /** Without this a hung webhook holds a delivery open and the retry that would have worked never runs. */
    timeout: Duration = 5.seconds,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : Channel {
    override val name = "slack"

    private val client = http.newBuilder().callTimeout(timeout.toJavaDuration()).build()

    override suspend fun send(notification: Notification) {
        val body = buildJsonObject { put("text", notification.text) }.toString()
        val request =
            Request
                .Builder()
                .url(webhookUrl)
                .post(body.toRequestBody(JSON))
                .build()

        client.newCall(request).executeAsync().use { response ->
            if (!response.isSuccessful) {
                throw SlackDeliveryError(response.code, withContext(io) { response.body.string() })
            }
        }
    }

    private companion object {
        /** What Slack's incoming webhooks expect. */
        val JSON = "application/json".toMediaType()
    }
}
