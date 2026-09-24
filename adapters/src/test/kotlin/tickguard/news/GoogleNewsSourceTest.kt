package tickguard.news

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tickguard.testing.failureOf

class GoogleNewsSourceTest {
    private val server = MockWebServer()

    @BeforeEach
    fun start() = server.start()

    @AfterEach
    fun stop() = server.close()

    private fun source() = GoogleNewsSource(OkHttpClient(), baseUrl = server.url("/").toString().trimEnd('/'))

    @Test
    fun `queries by symbol and reports a non-200 as an error`() =
        runTest {
            server.enqueue(MockResponse.Builder().code(503).build())

            val failure = failureOf { source().itemsFor("AMZN") }

            assertThat(failure).hasMessage("Google News returned 503 for AMZN")
            assertThat(server.takeRequest().url.toString()).contains("q=AMZN%20stock&hl=en-US&gl=US&ceid=US:en")
        }

    @Test
    fun `parses the feed it is given`() =
        runTest {
            server.enqueue(
                MockResponse
                    .Builder()
                    .body(
                        "<rss><channel><item><title>FTC sues Amazon - CNBC</title><link>https://x</link>" +
                            "<pubDate>Tue, 22 Sep 2026 17:10:22 GMT</pubDate>" +
                            "<source url=\"https://c\">CNBC</source></item></channel></rss>",
                    ).build(),
            )

            assertThat(source().itemsFor("AMZN").map { it.title }).containsExactly("FTC sues Amazon")
        }

    @Test
    fun `encodes a query as encodeURIComponent does, not as a form`() {
        assertThat(encodeUriComponent("BRK.B stock")).isEqualTo("BRK.B%20stock")
        assertThat(encodeUriComponent("a&b=c/d?é!*'()~")).isEqualTo("a%26b%3Dc%2Fd%3F%C3%A9!*'()~")
    }
}
