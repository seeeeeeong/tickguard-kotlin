package tickguard.rest

import kotlinx.serialization.json.JsonElement
import tickguard.network.ERROR_BODY_EXCERPT
import tickguard.network.StatusFailure
import java.io.IOException

/**
 * The one way this project calls the Toss REST API.
 *
 * Everything behind it exists because a rate limit is shared across call
 * sites: a holdings sync and a price reconciliation that each look reasonable
 * alone can exhaust a group together. Routing every call through one limiter
 * is the only place that can be seen.
 *
 * Read only. There is no method that can reach an order endpoint, which is a
 * cheaper guarantee than remembering not to call one.
 */
interface RestClient {
    val limiter: RateLimiter

    /** The parsed JSON body of a successful GET. */
    suspend fun get(spec: RequestSpec): JsonElement
}

data class RequestSpec(
    val path: String,
    val group: RateLimitGroup,
    /** A null value is left out, not sent as the text "null". */
    val query: Map<String, String?> = emptyMap(),
    /** Account, asset and order endpoints need the account header. */
    val withAccount: Boolean = false,
)

class RestError(
    override val status: Int,
    body: String,
    val retryable: Boolean,
) : IOException("Toss returned $status: ${body.take(ERROR_BODY_EXCERPT)}"),
    StatusFailure
