package tickguard.toss.rest

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tickguard.rest.RateLimitGroup
import tickguard.rest.RateLimiter
import tickguard.rest.RequestSpec
import tickguard.rest.RestError
import tickguard.testing.failureOf
import java.io.InterruptedIOException
import java.time.InstantSource
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class OkHttpRestClientTest {
    private val server = MockWebServer()
    private val slept = mutableListOf<Duration>()
    private val invalidated = mutableListOf<String>()

    @BeforeEach
    fun start() = server.start()

    @AfterEach
    fun stop() = server.close()

    private fun client(
        limiter: RateLimiter = RateLimiter(sleep = {}),
        timeout: Duration = 15.seconds,
    ) = OkHttpRestClient(
        http = OkHttpClient(),
        getToken = { "token" },
        invalidateToken = { invalidated += it },
        accountSeq = "3",
        limiter = limiter,
        baseUrl = server.url("/"),
        timeout = timeout,
        sleep = { slept += it },
    )

    private fun answer(
        code: Int,
        body: String = "{}",
        vararg headers: Pair<String, String>,
    ) = server.enqueue(
        MockResponse
            .Builder()
            .code(code)
            .body(body)
            .apply { headers.forEach { (name, value) -> addHeader(name, value) } }
            .build(),
    )

    private fun requests(): List<RecordedRequest> = List(server.requestCount) { server.takeRequest() }

    private val prices = RequestSpec("/api/v1/prices", RateLimitGroup.MARKET_DATA)

    @Test
    fun `sends the bearer token and builds the query`() =
        runTest {
            answer(200, """{"ok":true}""")

            val spec = RequestSpec("/api/v1/stocks", RateLimitGroup.STOCK, mapOf("symbols" to "005930"))
            val body = client().get(spec)

            val request = server.takeRequest()
            assertThat(
                request.url.encodedPath + "?" + request.url.encodedQuery,
            ).isEqualTo("/api/v1/stocks?symbols=005930")
            assertThat(request.headers["Authorization"]).isEqualTo("Bearer token")
            assertThat(body.jsonObject["ok"]).isEqualTo(JsonPrimitive(true))
        }

    @Test
    fun `adds the account header only where it belongs`() =
        runTest {
            answer(200)
            answer(200)

            client().get(RequestSpec("/api/v1/holdings", RateLimitGroup.ASSET, withAccount = true))
            client().get(prices)

            val (holdings, price) = requests()
            assertThat(holdings.headers["X-Tossinvest-Account"]).isEqualTo("3")
            assertThat(price.headers["X-Tossinvest-Account"]).isNull()
        }

    @Test
    fun `drops a null query value rather than sending the text`() =
        runTest {
            answer(200)

            client().get(RequestSpec("/api/v1/prices", RateLimitGroup.MARKET_DATA, mapOf("cursor" to null)))

            assertThat(server.takeRequest().url.encodedQuery).isNull()
        }

    @Test
    fun `waits the Retry-After the server asked for on 429`() =
        runTest {
            answer(429, "slow down", "Retry-After" to "2")
            answer(200)

            client().get(prices)

            assertThat(slept).containsExactly(2.seconds)
        }

    @Test
    fun `backs off on its own when 429 carries no Retry-After`() =
        runTest {
            answer(429)
            answer(200)

            client().get(prices)

            assertThat(slept).containsExactly(250.milliseconds)
        }

    @Test
    fun `retries a 500 and gives up as retryable`() =
        runTest {
            repeat(3) { answer(500, "nope") }

            val failure = failureOf { client().get(prices) } as RestError

            assertThat(failure.status).isEqualTo(500)
            assertThat(failure.retryable).isTrue()
            assertThat(server.requestCount).isEqualTo(3)
            assertThat(slept).containsExactly(250.milliseconds, 500.milliseconds)
        }

    @Test
    fun `does not retry a 4xx that will fail the same way`() =
        runTest {
            answer(403, "nope")

            assertThat(failureOf { client().get(prices) }).isInstanceOf(RestError::class.java)
            assertThat(server.requestCount).isEqualTo(1)
        }

    @Test
    fun `invalidates the token it used on 401, so the retry does not reuse a dead one`() =
        runTest {
            answer(401, "revoked")

            failureOf { client().get(prices) }

            assertThat(invalidated).containsExactly("token")
        }

    @Test
    fun `feeds the server's headroom back to the limiter`() =
        runTest {
            val limiter = RateLimiter(sleep = {})
            answer(200, "{}", "X-RateLimit-Remaining" to "0")
            answer(200)

            client(limiter).get(prices)
            client(limiter).get(prices)

            // MARKET_DATA allows 15/s, so the second call waited only because the server said none were left.
            assertThat(limiter.stats().waits).isEqualTo(1)
        }

    @Test
    fun `gives up on a request that never answers`() =
        runTest {
            // A connection the router's NAT dropped looks exactly like this.
            server.enqueue(MockResponse.Builder().headersDelay(10, TimeUnit.SECONDS).build())

            assertThat(failureOf { client(timeout = 100.milliseconds).get(prices) })
                .isInstanceOf(InterruptedIOException::class.java)
        }

    @Test
    fun `does not count time spent waiting for a rate-limit slot`() =
        runTest {
            withContext(Dispatchers.Default) {
                // ACCOUNT allows 1/s: the second call waits about a second for its slot,
                // far past a 300ms deadline that must not have started yet.
                val limiter = RateLimiter(InstantSource.system())
                answer(200)
                answer(200)
                val spec = RequestSpec("/api/v1/holdings", RateLimitGroup.ACCOUNT)

                client(limiter, timeout = 300.milliseconds).get(spec)
                val second = failureOf { client(limiter, timeout = 300.milliseconds).get(spec) }

                assertThat(second).isNull()
                assertThat(limiter.stats().waits).isEqualTo(1)
            }
        }

    @Test
    fun `refuses a successful body that is not JSON`() =
        runTest {
            answer(200, "<html>")

            assertThat(failureOf { client().get(prices) }).hasMessageContaining("not JSON")
        }
}
