package tickguard.toss.execution

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.coroutines.executeAsync
import tickguard.execution.OrderPlacer
import tickguard.execution.OrderRequest
import tickguard.execution.PlaceOutcome
import tickguard.json.StrictJson
import tickguard.network.reasonOf
import tickguard.rest.RateLimitGroup
import tickguard.rest.RateLimiter
import tickguard.toss.rest.OkHttpRestClient
import tickguard.trading.Side
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * The one place an order leaves this process: `POST /api/v1/orders`, a market
 * order, bought by dollar amount or sold by fractional quantity.
 *
 * Every answer is sorted by what it says about whether an order now exists.
 * A refusal the server states (a 4xx other than a key still in progress)
 * created nothing. A 5xx, a key in progress, an accepted answer without an
 * order id, or a connection that failed once the request was on its way may
 * each have created one, and are reported as unknown for the executor to
 * halt on. Nothing is retried here: the client order id makes a person's
 * retry safe, and an automatic one is how a fault repeats itself.
 */
class TossOrderPlacer(
    http: OkHttpClient,
    private val getToken: suspend () -> String,
    private val invalidateToken: suspend (String) -> Unit,
    private val accountSeq: String,
    private val limiter: RateLimiter,
    private val baseUrl: HttpUrl = OkHttpRestClient.REST_BASE_URL.toHttpUrl(),
    timeout: Duration = ORDER_TIMEOUT,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : OrderPlacer {
    private val client = http.newBuilder().callTimeout(timeout.toJavaDuration()).build()

    @Suppress("TooGenericExceptionCaught") // Any failure before sending means no order; any after, an unknown one.
    override suspend fun place(request: OrderRequest): PlaceOutcome {
        val token =
            try {
                getToken()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                return PlaceOutcome.Refused(0, "not-sent", "no token: ${reasonOf(failure)}")
            }
        limiter.acquire(RateLimitGroup.ORDER)
        val call =
            client.newCall(
                Request
                    .Builder()
                    .url(baseUrl.newBuilder().encodedPath(PATH).build())
                    .header("Authorization", "Bearer $token")
                    .header("X-Tossinvest-Account", accountSeq)
                    .post(body(request).toRequestBody(JSON))
                    .build(),
            )
        return try {
            call.executeAsync().use { response ->
                val text = withContext(io) { response.body.string() }
                if (response.code == HTTP_UNAUTHORIZED) invalidateToken(token)
                outcome(response.code, text)
            }
        } catch (failure: IOException) {
            PlaceOutcome.Unknown("no answer after sending: ${reasonOf(failure)}")
        }
    }

    private companion object {
        /** Order creation, the one order endpoint this project calls. */
        const val PATH = "/api/v1/orders"

        /** What the orders endpoint takes. */
        val JSON = "application/json".toMediaType()
    }
}

/** An order is a click that cannot be taken back; a slow answer is waited for longer than a read. */
private val ORDER_TIMEOUT = 20.seconds

/** A token the server no longer takes: no order was created, and the next call gets a fresh one. */
private const val HTTP_UNAUTHORIZED = 401

/** Refusals the server states: at or above this and below [HTTP_SERVER_ERROR]. */
private const val HTTP_CLIENT_ERROR = 400

/** The server's own trouble: it may have created the order before failing. */
private const val HTTP_SERVER_ERROR = 500

/** 200 with an order id is the only answer that says an order exists and which one. */
private const val HTTP_OK = 200

/** A request with the same key is still being processed: the first may yet create the order. */
private const val IN_PROGRESS = "request-in-progress"

internal fun body(request: OrderRequest): String =
    buildJsonObject {
        put("clientOrderId", request.clientOrderId)
        put("symbol", request.symbol)
        put("side", request.side.name)
        put("orderType", "MARKET")
        when (request.side) {
            Side.BUY -> put("orderAmount", request.amount?.toPlainString())
            Side.SELL -> put("quantity", request.quantity?.toPlainString())
        }
    }.toString()

internal fun outcome(
    status: Int,
    text: String,
): PlaceOutcome {
    val json = StrictJson.parse(text) as? JsonObject
    if (status == HTTP_OK) {
        val id =
            (
                (
                    json?.get(
                        "result",
                    ) as? JsonObject
                )?.get("orderId") as? JsonPrimitive
            )?.takeIf { it.isString }?.content
        return id?.let { PlaceOutcome.Placed(it) } ?: PlaceOutcome.Unknown("accepted without an order id")
    }
    val error = json?.get("error") as? JsonObject
    val code = (error?.get("code") as? JsonPrimitive)?.content ?: "status-$status"
    val message = (error?.get("message") as? JsonPrimitive)?.content.orEmpty()
    return if (status in HTTP_CLIENT_ERROR until HTTP_SERVER_ERROR && code != IN_PROGRESS) {
        PlaceOutcome.Refused(status, code, message)
    } else {
        PlaceOutcome.Unknown("$status $code $message".trim())
    }
}
