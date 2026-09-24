package tickguard.verdict.deepseek

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import okhttp3.OkHttpClient
import tickguard.json.StrictJson
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Plain Korean translations of headlines, for the person labelling them.
 *
 * Deliberately not the verdict's summary. That sentence is written in the
 * same answer as the relevance judgement and carries it ("…, 알파벳과 직접
 * 관련이 없다"), so showing it would anchor the labels on the very answers
 * they are meant to check. This asks for translation only, in a separate
 * request that never sees a verdict.
 *
 * Headlines go in batches, in order, and come back as an array. A batch
 * whose count does not match is discarded rather than trusted: one dropped
 * line would shift every translation after it onto the wrong headline.
 */
class Translator(
    apiKey: String,
    http: OkHttpClient,
    model: String = "deepseek-flash",
    baseUrl: String = "https://api.deepseek.com",
    private val batchSize: Int = DEFAULT_BATCH,
    timeout: Duration = 60.seconds,
    io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val chat =
        ChatCompletions(http.newBuilder().callTimeout(timeout.toJavaDuration()).build(), baseUrl, apiKey, model, io)

    /** One translation per headline, in order; null where its batch was discarded. */
    suspend fun translate(headlines: List<String>): List<String?> =
        headlines.chunked(batchSize).flatMap { batch -> translateBatch(batch) ?: batch.map { null } }

    private suspend fun translateBatch(batch: List<String>): List<String>? {
        val question = buildJsonObject { putJsonArray("headlines") { batch.forEach { add(JsonPrimitive(it)) } } }
        val content =
            chat.complete(PROMPT, question.toString()) { status, _ ->
                "translation returned $status"
            } ?: return null
        val translations =
            ((StrictJson.parse(content) as? JsonObject)?.get("translations") as? JsonArray) ?: return null
        val lines = translations.map { (it as? JsonPrimitive)?.takeIf { line -> line.isString }?.content }
        return if (lines.size == batch.size && lines.none { it == null }) lines.filterNotNull() else null
    }

    private companion object {
        /** Twenty-five headlines to a request keeps each answer short enough to trust its count. */
        const val DEFAULT_BATCH = 25

        /** Translation only: no judgement the labels could anchor on. */
        const val PROMPT = """Translate each news headline into natural Korean.
Translate faithfully. Do not add commentary, judgement or explanation.
Keep ticker symbols, company names in common Korean usage, and numbers.
Reply with a JSON object only: {"translations": [string, ...]}, one per input headline, in the same order."""
    }
}
