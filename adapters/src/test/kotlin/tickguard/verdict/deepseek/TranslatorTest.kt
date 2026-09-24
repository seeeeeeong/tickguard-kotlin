package tickguard.verdict.deepseek

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.putJsonArray
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tickguard.json.StrictJson
import java.util.concurrent.CopyOnWriteArrayList

class TranslatorTest {
    private val server = MockWebServer()
    private val questions = CopyOnWriteArrayList<Set<String>>()
    private val sent = CopyOnWriteArrayList<List<String>>()

    @AfterEach
    fun stop() = server.close()

    /** Answers each batch with whatever [reply] makes of its headlines. */
    private fun fakeTranslator(reply: (List<String>) -> List<String>) {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = StrictJson.parse(request.body!!.utf8())!!.jsonObject
                    val question =
                        StrictJson
                            .parse(
                                body["messages"]!!
                                    .jsonArray[1]
                                    .jsonObject["content"]!!
                                    .jsonPrimitive.content,
                            )!!
                            .jsonObject
                    questions += question.keys
                    val headlines = question["headlines"]!!.jsonArray.map { it.jsonPrimitive.content }
                    sent += headlines
                    val answer =
                        buildJsonObject {
                            putJsonArray(
                                "translations",
                            ) { reply(headlines).forEach { add(JsonPrimitive(it)) } }
                        }
                    return completion(answer.toString())
                }
            }
        server.start()
    }

    private fun translator(batchSize: Int = 2) =
        Translator("k", OkHttpClient(), baseUrl = server.url("/").toString().trimEnd('/'), batchSize = batchSize)

    @Test
    fun `translates in order, batch by batch`() =
        runTest {
            fakeTranslator { headlines -> headlines.map { "KO:$it" } }

            val out = translator().translate(listOf("a", "b", "c"))

            assertThat(out).containsExactly("KO:a", "KO:b", "KO:c")
            assertThat(sent).containsExactly(listOf("a", "b"), listOf("c"))
        }

    @Test
    fun `discards a batch whose count is off, rather than shifting lines onto the wrong headline`() =
        runTest {
            fakeTranslator { headlines -> if (headlines.size == 2) listOf("only one") else headlines.map { "KO:$it" } }

            val out = translator().translate(listOf("a", "b", "c"))

            assertThat(out).containsExactly(null, null, "KO:c")
        }

    @Test
    fun `never sends anything but the headlines`() =
        runTest {
            fakeTranslator { it }

            translator(batchSize = 25).translate(listOf("FTC sues Amazon"))

            assertThat(questions.single()).containsExactly("headlines")
        }
}
