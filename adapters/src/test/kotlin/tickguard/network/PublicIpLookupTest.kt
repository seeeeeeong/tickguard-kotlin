package tickguard.network

import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class PublicIpLookupTest {
    private val server = MockWebServer()

    @AfterEach
    fun stop() = server.close()

    /** Each source answers with a fixed body or a status. */
    private fun lookup(vararg answers: Pair<String, Any>): PublicIpLookup {
        val byPath = answers.toMap()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    when (val answer = byPath[request.url.encodedPath]) {
                        is Int -> MockResponse.Builder().code(answer).build()
                        else -> MockResponse.Builder().body(answer.toString()).build()
                    }
            }
        server.start()
        return PublicIpLookup(OkHttpClient(), answers.map { server.url(it.first).toString() })
    }

    @Test
    fun `reports an address both sources agree on`() =
        runTest {
            val result = lookup("/a" to "203.0.113.7\n", "/b" to "203.0.113.7").lookup()

            assertThat(result).isEqualTo(PublicIp("203.0.113.7", listOf("203.0.113.7"), 0))
        }

    @Test
    fun `refuses to pick when the sources disagree`() =
        runTest {
            val result = lookup("/a" to "203.0.113.7", "/b" to "198.51.100.9").lookup()

            assertThat(result.ip).isNull()
            assertThat(result.answers).containsExactly("203.0.113.7", "198.51.100.9")
        }

    @Test
    fun `uses a single answer but counts the source that failed`() =
        runTest {
            val result = lookup("/a" to "203.0.113.7", "/b" to 503).lookup()

            assertThat(result).isEqualTo(PublicIp("203.0.113.7", listOf("203.0.113.7"), 1))
        }

    @Test
    fun `discards an IPv6 answer, which the allow list cannot use`() =
        runTest {
            val result = lookup("/a" to "2606:4700::6810:b9f1", "/b" to 503).lookup()

            assertThat(result).isEqualTo(PublicIp(null, emptyList(), 2))
        }

    @Test
    fun `discards anything that is not a dotted quad`() =
        runTest {
            val result = lookup("/a" to "<html>captive portal</html>", "/b" to "999.1.1.1").lookup()

            assertThat(result.answers).isEmpty()
        }

    @Test
    fun `reports a failed lookup rather than throwing`() =
        runTest {
            val unreachable = PublicIpLookup(OkHttpClient(), listOf("http://127.0.0.1:1/", "http://127.0.0.1:1/"))

            assertThat(unreachable.lookup()).isEqualTo(PublicIp(null, emptyList(), 2))
        }
}
