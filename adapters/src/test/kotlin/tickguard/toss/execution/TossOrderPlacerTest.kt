package tickguard.toss.execution

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tickguard.execution.OrderRequest
import tickguard.execution.PlaceOutcome
import tickguard.rest.RateLimiter
import tickguard.stream.Decimal
import tickguard.trading.Side
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

class TossOrderPlacerTest {
    private val server = MockWebServer()
    private val invalidated = mutableListOf<String>()

    @BeforeEach
    fun start() = server.start()

    @AfterEach
    fun stop() = server.close()

    private fun placer(timeoutMs: Long = 5_000) =
        TossOrderPlacer(
            OkHttpClient(),
            getToken = { "t" },
            invalidateToken = { invalidated += it },
            accountSeq = "7",
            limiter = RateLimiter(sleep = {}),
            baseUrl = server.url("/"),
            timeout = timeoutMs.milliseconds,
        )

    private val buy = OrderRequest("tg-20261102-A-B-SPY", "A", "SPY", Side.BUY, Decimal.parse("30.69", "a"), null)
    private val sell = OrderRequest("tg-20261102-A-S-TLT", "A", "TLT", Side.SELL, null, Decimal.parse("0.386405", "q"))

    private fun answer(
        code: Int,
        body: String,
    ) = server.enqueue(
        MockResponse
            .Builder()
            .code(code)
            .body(body)
            .build(),
    )

    @Test
    fun `buys by dollar amount at market, with the key and the account`() =
        runTest {
            answer(200, """{"result":{"orderId":"o1","clientOrderId":"tg-20261102-A-B-SPY"}}""")

            assertThat(placer().place(buy)).isEqualTo(PlaceOutcome.Placed("o1"))

            val request = server.takeRequest()
            assertThat(request.method).isEqualTo("POST")
            assertThat(request.url.encodedPath).isEqualTo("/api/v1/orders")
            assertThat(request.headers["X-Tossinvest-Account"]).isEqualTo("7")
            assertThat(request.headers["Authorization"]).isEqualTo("Bearer t")
            val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
            assertThat(body.mapValues { it.value.jsonPrimitive.content }).isEqualTo(
                mapOf(
                    "clientOrderId" to "tg-20261102-A-B-SPY",
                    "symbol" to "SPY",
                    "side" to "BUY",
                    "orderType" to "MARKET",
                    "orderAmount" to "30.69",
                ),
            )
        }

    @Test
    fun `sells by fractional quantity`() =
        runTest {
            answer(200, """{"result":{"orderId":"o2"}}""")

            placer().place(sell)

            val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
            assertThat(body["quantity"]?.jsonPrimitive?.content).isEqualTo("0.386405")
            assertThat(body["orderAmount"]).isNull()
        }

    @Test
    fun `takes a stated refusal as no order, and anything that may have made one as unknown`() {
        assertThat(outcome(422, """{"error":{"code":"insufficient-buying-power","message":"부족"}}"""))
            .isEqualTo(PlaceOutcome.Refused(422, "insufficient-buying-power", "부족"))
        assertThat(
            outcome(409, """{"error":{"code":"opposite-pending-order-exists","message":"m"}}"""),
        ).isInstanceOf(PlaceOutcome.Refused::class.java)
        assertThat(
            outcome(409, """{"error":{"code":"request-in-progress","message":"m"}}"""),
        ).isInstanceOf(PlaceOutcome.Unknown::class.java)
        assertThat(
            outcome(500, """{"error":{"code":"internal-error","message":"m"}}"""),
        ).isInstanceOf(PlaceOutcome.Unknown::class.java)
        assertThat(outcome(200, """{"result":{}}""")).isEqualTo(PlaceOutcome.Unknown("accepted without an order id"))
        assertThat(outcome(503, "<html>")).isInstanceOf(PlaceOutcome.Unknown::class.java)
    }

    @Test
    fun `gives a refused token back and reports no order`() =
        runTest {
            answer(401, """{"error":{"code":"token-revoked","message":"m"}}""")

            assertThat(placer().place(buy)).isInstanceOf(PlaceOutcome.Refused::class.java)
            assertThat(invalidated).containsExactly("t")
        }

    @Test
    fun `reports a request that got no answer as unknown, since it may have been placed`() =
        runTest {
            server.enqueue(MockResponse.Builder().headersDelay(10, TimeUnit.SECONDS).build())

            assertThat(placer(timeoutMs = 100).place(buy)).isInstanceOf(PlaceOutcome.Unknown::class.java)
        }

    @Test
    fun `reports a dropped connection after sending as unknown`() =
        runTest {
            server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())

            assertThat(placer().place(buy)).isInstanceOf(PlaceOutcome.Unknown::class.java)
        }

    @Test
    fun `sends nothing without a token, and says no order exists`() =
        runTest {
            val noToken =
                TossOrderPlacer(
                    OkHttpClient(),
                    { error("issuer down") },
                    {},
                    "7",
                    RateLimiter(sleep = {}),
                    server.url("/"),
                )

            assertThat(noToken.place(buy)).isInstanceOf(PlaceOutcome.Refused::class.java)
            assertThat(server.requestCount).isZero()
        }
}
