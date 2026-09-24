package tickguard.verdict.deepseek

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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
import tickguard.verdict.Judge
import tickguard.verdict.JudgeInput
import tickguard.verdict.Verdict
import tickguard.verdict.VerdictFormatError
import tickguard.verdict.parseVerdict
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Asks DeepSeek whether a headline matters to a stock.
 *
 * Only public information leaves this process: the symbol, the headline, its
 * publisher and source. Positions — average price, quantity, anything about
 * the account — never do. What a verdict means for what is held is worked
 * out locally, which also keeps the model's task narrow.
 *
 * The API is OpenAI-compatible, so this is one request rather than an SDK.
 * Temperature 0 and JSON mode, because the answer is data to be compared
 * against a threshold, not prose.
 */
class DeepSeekJudge(
    apiKey: String,
    http: OkHttpClient,
    /** Checked against GET /models: deepseek-flash and deepseek-v4-pro were offered. */
    override val model: String = "deepseek-flash",
    baseUrl: String = "https://api.deepseek.com",
    /** Two live calls took ~1.4 s each; thirty leaves room for a slow day. */
    timeout: Duration = 30.seconds,
    io: CoroutineDispatcher = Dispatchers.IO,
) : Judge {
    private val chat =
        ChatCompletions(http.newBuilder().callTimeout(timeout.toJavaDuration()).build(), baseUrl, apiKey, model, io)

    override suspend fun judge(item: JudgeInput): Verdict {
        val question =
            buildJsonObject {
                put("symbol", item.code)
                put("headline", item.title)
                put("publisher", item.publisher)
                put("source", item.source)
            }
        val content =
            chat.complete(SYSTEM_PROMPT, question.toString()) { status, body ->
                "DeepSeek returned $status: ${body.take(ERROR_BODY_EXCERPT)}"
            }
        val parsed =
            StrictJson.parse(content ?: throw VerdictFormatError("no message content"))
                ?: throw VerdictFormatError("content is not JSON: ${content.take(CONTENT_EXCERPT)}")
        return parseVerdict(parsed)
    }

    companion object {
        /** The instruction, as the original sent it. */
        const val SYSTEM_PROMPT = """You judge whether a news headline is likely to move a specific stock's price.
Reply with a JSON object only: {"relevant": boolean, "direction": "up" | "down" | "neutral", "impact": number from 0 to 1, "summary": string}.
relevant is false for filler: fund position disclosures, price predictions, listicles, generic market recaps.
impact: 0 means no effect on the price; 1 means a major event such as an earnings surprise, regulatory action, a merger, an executive departure or a lawsuit.
summary: one short Korean sentence stating what happened, without speculation."""

        /** How much of an unreadable answer an error quotes. */
        private const val CONTENT_EXCERPT = 100
    }
}
