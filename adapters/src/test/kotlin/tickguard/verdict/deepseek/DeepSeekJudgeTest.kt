package tickguard.verdict.deepseek

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tickguard.json.StrictJson
import tickguard.testing.failureOf
import tickguard.verdict.Direction
import tickguard.verdict.JudgeInput
import tickguard.verdict.Verdict
import tickguard.verdict.VerdictFormatError

private val story =
    JudgeInput(code = "AMZN", title = "FTC sues Amazon over ad fees", publisher = "CNBC", source = "google-news")

/** A chat completion carrying [content] as its first message. */
internal fun completion(content: String) =
    MockResponse
        .Builder()
        .body(
            buildJsonObject {
                putJsonArray("choices") { addJsonObject { putJsonObject("message") { put("content", content) } } }
                putJsonObject("usage") {
                    put("prompt_tokens", 211)
                    put("completion_tokens", 95)
                }
            }.toString(),
        ).build()

class DeepSeekJudgeTest {
    private val server = MockWebServer()

    @BeforeEach
    fun start() = server.start()

    @AfterEach
    fun stop() = server.close()

    private fun judge() = DeepSeekJudge("k", OkHttpClient(), baseUrl = server.url("/").toString().trimEnd('/'))

    private fun sentBody() = StrictJson.parse(server.takeRequest().body!!.utf8()) as JsonObject

    @Test
    fun `asks in JSON mode at temperature 0 and parses the answer`() =
        runTest {
            server.enqueue(completion("""{"relevant":true,"direction":"down","impact":0.72,"summary":"FTC가 제소했다."}"""))

            val verdict = judge().judge(story)

            assertThat(verdict).isEqualTo(Verdict(true, Direction.DOWN, 0.72, "FTC가 제소했다."))
            val request = server.takeRequest()
            assertThat(request.url.encodedPath).isEqualTo("/chat/completions")
            assertThat(request.headers["Authorization"]).isEqualTo("Bearer k")
            val body = StrictJson.parse(request.body!!.utf8())!!.jsonObject
            assertThat(body["model"]).isEqualTo(JsonPrimitive("deepseek-flash"))
            assertThat(body["temperature"]).isEqualTo(JsonPrimitive(0))
            assertThat(body["response_format"]).isEqualTo(buildJsonObject { put("type", "json_object") })
        }

    @Test
    fun `sends only public information about the story`() =
        runTest {
            // Positions never leave the process; what a verdict means for them is
            // worked out locally. JudgeInput has no field to carry one.
            server.enqueue(completion("""{"relevant":false,"direction":"neutral","impact":0,"summary":"x"}"""))

            judge().judge(story)

            val messages = sentBody()["messages"] as JsonArray
            val question = StrictJson.parse(messages[1].jsonObject["content"]!!.jsonPrimitive.content)!!.jsonObject
            assertThat(question.keys.sorted()).containsExactly("headline", "publisher", "source", "symbol")
        }

    @Test
    fun `reports an HTTP failure with its status`() =
        runTest {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(402)
                    .body("""{"error":"Insufficient Balance"}""")
                    .build(),
            )

            assertThat(failureOf { judge().judge(story) })
                .hasMessage("""DeepSeek returned 402: {"error":"Insufficient Balance"}""")
        }

    @Test
    fun `treats content that is not JSON as a format error, not a verdict`() =
        runTest {
            server.enqueue(completion("Sure! Here is"))

            assertThat(failureOf { judge().judge(story) })
                .isInstanceOf(VerdictFormatError::class.java)
                .hasMessage("content is not JSON: Sure! Here is")
        }
}
