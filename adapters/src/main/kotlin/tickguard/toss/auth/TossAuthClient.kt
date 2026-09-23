package tickguard.toss.auth

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.coroutines.executeAsync
import tickguard.auth.IssuedToken
import tickguard.auth.MalformedTokenResponseError
import tickguard.auth.TokenIssueError
import tickguard.auth.TokenIssuer
import tickguard.auth.TossCredentials
import tickguard.json.StrictJson
import java.time.Instant
import java.time.InstantSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

class TossAuthClient(
    private val credentials: TossCredentials,
    private val clock: InstantSource,
    http: OkHttpClient,
    private val tokenUrl: HttpUrl = TOKEN_URL.toHttpUrl(),
    /** Same reasoning as the REST deadline: a stalled issue stalls every caller waiting on it. */
    timeout: Duration = 15.seconds,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : TokenIssuer {
    private val client = http.newBuilder().callTimeout(timeout.toJavaDuration()).build()

    override suspend fun issue(): IssuedToken {
        val requestedAt = clock.instant()
        val request =
            Request
                .Builder()
                .url(tokenUrl)
                .post(
                    FormBody
                        .Builder()
                        .add("grant_type", "client_credentials")
                        .add("client_id", credentials.clientId)
                        .add("client_secret", credentials.clientSecret)
                        .build(),
                ).build()

        return client.newCall(request).executeAsync().use { response ->
            val body = withContext(io) { response.body.string() }
            if (!response.isSuccessful) throw TokenIssueError.fromResponse(response.code, body)
            toIssuedToken(body, requestedAt)
        }
    }

    companion object {
        /** The token endpoint. The only Toss call in this project that sends a body. */
        const val TOKEN_URL = "https://openapi.tossinvest.com/oauth2/token"
    }
}

/**
 * expires_in is relative to when the server answered, but the clock we have is
 * from before the request went out. Anchoring on the earlier timestamp makes the
 * expiry conservative by however long the round trip took, which is the safe
 * direction: refreshing early costs one request, refreshing late costs a 401.
 */
internal fun toIssuedToken(
    body: String,
    requestedAt: Instant,
): IssuedToken {
    val parsed = StrictJson.parse(body) ?: malformed("not JSON")
    val fields = parsed as? JsonObject ?: malformed("not an object")

    val accessToken =
        (fields["access_token"] as? JsonPrimitive)
            ?.takeIf { it.isString && it.content.isNotEmpty() }
            ?.content ?: malformed("access_token missing")

    val expiresIn =
        (fields["expires_in"] as? JsonPrimitive)
            ?.takeUnless { it.isString }
            ?.doubleOrNull
            ?.takeIf { it.isFinite() && it > 0 } ?: malformed("expires_in missing or not a positive number")

    return IssuedToken(accessToken, requestedAt.plus(expiresIn.seconds.toJavaDuration()))
}

private fun malformed(detail: String): Nothing = throw MalformedTokenResponseError(detail)
