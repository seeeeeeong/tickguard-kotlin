package tickguard.verdict.deepseek

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.coroutines.executeAsync
import tickguard.json.StrictJson
import tickguard.network.ERROR_BODY_EXCERPT
import java.io.IOException

/**
 * DeepSeek's OpenAI-compatible chat endpoint, asked the way both callers ask:
 * JSON mode at temperature 0, one system and one user message.
 */
internal class ChatCompletions(
    /** Carries the caller's timeout: a verdict and a batch of translations take different time. */
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val io: CoroutineDispatcher,
) {
    /**
     * The message content, or null when the answer carries none. A refused
     * request throws with whatever [refusal] makes of its status and body.
     */
    suspend fun complete(
        system: String,
        user: String,
        refusal: (status: Int, body: String) -> String,
    ): String? {
        val body =
            buildJsonObject {
                put("model", model)
                put("temperature", 0)
                putJsonObject("response_format") { put("type", "json_object") }
                putJsonArray("messages") {
                    addJsonObject {
                        put("role", "system")
                        put("content", system)
                    }
                    addJsonObject {
                        put("role", "user")
                        put("content", user)
                    }
                }
            }.toString()
        val request =
            Request
                .Builder()
                .url("$baseUrl/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .post(body.toRequestBody(JSON))
                .build()

        return client.newCall(request).executeAsync().use { response ->
            val text = withContext(io) { response.body.string() }
            if (!response.isSuccessful) throw IOException(refusal(response.code, text))
            messageContent(
                StrictJson.parse(text) ?: throw IOException("the answer is not JSON: ${text.take(ERROR_BODY_EXCERPT)}"),
            )
        }
    }

    private fun messageContent(body: JsonElement): String? {
        val choice = ((body as? JsonObject)?.get("choices") as? JsonArray)?.firstOrNull() as? JsonObject
        val message = choice?.get("message") as? JsonObject
        return (message?.get("content") as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    private companion object {
        /** What the chat completions endpoint expects. */
        val JSON = "application/json".toMediaType()
    }
}
