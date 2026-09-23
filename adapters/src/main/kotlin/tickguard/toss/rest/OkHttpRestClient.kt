package tickguard.toss.rest

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.coroutines.executeAsync
import tickguard.json.StrictJson
import tickguard.rest.RateLimiter
import tickguard.rest.RequestSpec
import tickguard.rest.RestClient
import tickguard.rest.RestError
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/** The Toss REST API over OkHttp. GET only: there is no way to express anything else here. */
class OkHttpRestClient(
    http: OkHttpClient,
    private val getToken: suspend () -> String,
    /** Called after a 401 with the token that was refused. */
    private val invalidateToken: suspend (String) -> Unit = {},
    private val accountSeq: String? = null,
    override val limiter: RateLimiter = RateLimiter(),
    private val baseUrl: HttpUrl = REST_BASE_URL.toHttpUrl(),
    /** Total attempts for a retryable failure, including the first. */
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    timeout: Duration = REST_TIMEOUT,
    private val sleep: suspend (Duration) -> Unit = { delay(it) },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : RestClient {
    private val client = http.newBuilder().callTimeout(timeout.toJavaDuration()).build()

    override suspend fun get(spec: RequestSpec): JsonElement {
        for (attempt in 1..maxAttempts) {
            val outcome = attempt(spec, attempt)
            outcome.body?.let { return it }
            sleep(outcome.wait ?: Duration.ZERO)
        }
        throw RestError(0, "Gave up after $maxAttempts attempts on ${spec.path}", retryable = true)
    }

    private class Outcome(
        val body: JsonElement? = null,
        val wait: Duration? = null,
    )

    private suspend fun attempt(
        spec: RequestSpec,
        attempt: Int,
    ): Outcome {
        val token = getToken()
        return send(spec, token).use { response ->
            response.header("X-RateLimit-Remaining")?.toDoubleOrNull()?.let { limiter.observe(spec.group, it) }
            val text = withContext(io) { response.body.string() }

            if (response.isSuccessful) {
                val body =
                    StrictJson.parse(text)
                        ?: throw IOException("Toss answered ${spec.path} with a body that is not JSON")
                return Outcome(body = body)
            }
            if (response.code == 401) invalidateToken(token)

            val retryable = response.code == 429 || response.code >= 500
            if (!retryable || attempt == maxAttempts) throw RestError(response.code, text, retryable)
            Outcome(wait = waitFor(response, attempt))
        }
    }

    private suspend fun send(
        spec: RequestSpec,
        token: String,
    ): Response {
        limiter.acquire(spec.group)

        val url =
            baseUrl
                .newBuilder()
                .encodedPath(spec.path)
                .apply { spec.query.forEach { (key, value) -> if (value != null) addQueryParameter(key, value) } }
                .build()
        val request =
            Request
                .Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .apply { if (spec.withAccount && accountSeq != null) header("X-Tossinvest-Account", accountSeq) }
                .build()

        // The call and its deadline start here, after the limiter: time spent
        // queued for a rate-limit slot is ours, not the server's.
        return client.newCall(request).executeAsync()
    }

    /** 429 states how long to wait; everything else uses our own backoff. */
    private fun waitFor(
        response: Response,
        attempt: Int,
    ): Duration {
        val retryAfter = response.header("Retry-After")?.toDoubleOrNull()
        if (retryAfter != null && retryAfter.isFinite() && retryAfter > 0) return retryAfter.seconds
        return FIRST_BACKOFF * (1 shl (attempt - 1))
    }

    companion object {
        /** The Toss REST host. */
        const val REST_BASE_URL = "https://openapi.tossinvest.com"

        /**
         * Every call gets a deadline. Without one, a request into a connection the
         * router's NAT has silently dropped waits on the transport's own limits —
         * minutes — and the scheduler, which never stacks a task on itself, skips
         * every poll of that task until it ends. Fifteen seconds is long for these
         * endpoints and short against a five-minute poll.
         */
        val REST_TIMEOUT = 15.seconds

        /** Three tries rides out a blip; a fourth would only spend the group's budget. */
        private const val DEFAULT_MAX_ATTEMPTS = 3

        /** Doubled per retry when the server does not say how long to wait. */
        private val FIRST_BACKOFF = 250.milliseconds
    }
}
