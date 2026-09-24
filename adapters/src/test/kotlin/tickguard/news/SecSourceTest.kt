package tickguard.news

import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tickguard.testing.failureOf
import java.time.Instant
import java.time.InstantSource
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.toJavaDuration

private const val SUBMISSIONS =
    """{"filings":{"recent":{"form":["8-K"],"accessionNumber":["0001018724-26-000081"],""" +
        """"acceptanceDateTime":["2026-07-30T20:01:05.000Z"],"filingDate":["2026-07-30"],""" +
        """"primaryDocument":["amzn-20260730.htm"],"items":["2.02,9.01"]}}}"""

class SecSourceTest {
    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()

    @AfterEach
    fun stop() = server.close()

    /** Serves [tickers] as the ticker map and one 8-K for every CIK. */
    private fun fakeSec(tickers: String) {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    val body = if (request.url.encodedPath.endsWith("company_tickers.json")) tickers else SUBMISSIONS
                    return MockResponse.Builder().body(body).build()
                }
            }
        server.start()
    }

    private fun source(
        contact: String = "tickguard ops@example.com",
        clock: InstantSource = InstantSource.system(),
    ) = SecSource(
        contact,
        OkHttpClient(),
        clock = clock,
        tickersUrl = server.url("/files/company_tickers.json").toString(),
        submissionsBase = server.url("/").toString().trimEnd('/'),
    )

    @Test
    fun `declares the configured contact on every request, as SEC requires`() =
        runTest {
            fakeSec("""{"0":{"cik_str":1018724,"ticker":"AMZN"}}""")

            val items = source().itemsFor("AMZN")

            assertThat(requests.map { it.headers["User-Agent"] })
                .containsExactly("tickguard ops@example.com", "tickguard ops@example.com")
            assertThat(requests[1].url.encodedPath).isEqualTo("/submissions/CIK0001018724.json")
            assertThat(items.map { it.title }).containsExactly("8-K · 실적 발표")
        }

    @Test
    fun `treats a ticker SEC does not know as having no filings`() =
        runTest {
            // Some funds and foreign listings are not SEC registrants.
            fakeSec("{}")

            assertThat(source().itemsFor("SPCX")).isEmpty()
            assertThat(requests).hasSize(1)
        }

    @Test
    fun `fetches the ticker map once a day, not once per symbol`() =
        runTest {
            fakeSec("""{"0":{"cik_str":1,"ticker":"AMZN"},"1":{"cik_str":2,"ticker":"GOOGL"}}""")
            val source = source()

            source.itemsFor("AMZN")
            source.itemsFor("GOOGL")

            assertThat(requests.filter { it.url.encodedPath.endsWith("company_tickers.json") }).hasSize(1)
        }

    @Test
    fun `pauses SEC for an hour instead of polling into a 403`() =
        runTest {
            // SEC blocks per address; a shared network can arrive already blocked.
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        requests += request
                        return MockResponse
                            .Builder()
                            .code(
                                403,
                            ).body("<html>Request Rate Threshold Exceeded</html>")
                            .build()
                    }
                }
            server.start()
            var now = Instant.EPOCH
            val source = source("ops@example.com") { now }

            assertThat(failureOf { source.itemsFor("AMZN") }).hasMessageContaining("403")
            assertThat(source.itemsFor("GOOGL")).isEmpty()
            now += (SEC_BLOCK_PAUSE - 1.milliseconds).toJavaDuration()
            assertThat(source.itemsFor("SPCX")).isEmpty()
            assertThat(requests).hasSize(1)
            assertThat(source.pausedUntil()).isEqualTo(Instant.EPOCH + SEC_BLOCK_PAUSE.toJavaDuration())

            now += 1.milliseconds.toJavaDuration()
            assertThat(failureOf { source.itemsFor("AMZN") }).hasMessageContaining("403")
            assertThat(requests).hasSize(2)
        }
}
