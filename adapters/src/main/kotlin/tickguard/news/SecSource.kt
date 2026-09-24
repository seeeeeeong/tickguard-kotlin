package tickguard.news

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.coroutines.executeAsync
import tickguard.json.StrictJson
import java.io.IOException
import java.time.Instant
import java.time.InstantSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * 8-K filings from SEC EDGAR.
 *
 * SEC's fair-access rules require a User-Agent naming who is asking and a
 * reachable email, and they are enforced: an undeclared request got a 403
 * page citing them. The contact comes from configuration, never from code,
 * so nobody running this repo sends requests under someone else's address.
 * The same rules cap traffic at 10 requests a second; a few symbols polled
 * every few minutes is orders of magnitude below it.
 */
class SecSource(
    /** `tickguard you@example.com`, or an address alone. Sent as the User-Agent. */
    contact: String,
    http: OkHttpClient,
    private val clock: InstantSource = InstantSource.system(),
    private val tickersUrl: String = TICKERS_URL,
    private val submissionsBase: String = "https://data.sec.gov",
    timeout: Duration = 10.seconds,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : NewsSource {
    override val name = NewsSourceName.SEC

    private val agent = userAgent(contact)
    private val client = http.newBuilder().callTimeout(timeout.toJavaDuration()).build()
    private var ciks: Map<String, String>? = null
    private var ciksLoadedAt: Instant = Instant.EPOCH

    @Volatile private var pausedUntil: Instant? = null

    /** When SEC is left alone until after a 403, if it is. */
    fun pausedUntil(): Instant? = pausedUntil?.takeIf { clock.instant() < it }

    override suspend fun itemsFor(code: String): List<NewsItem> {
        // Quietly nothing while paused: the 403 that started the pause was
        // already reported once, and reporting it per symbol per poll is noise.
        if (pausedUntil() != null) return emptyList()
        // Not every ticker is an SEC registrant — some funds and foreign listings
        // are not — and those simply have no filings to watch.
        val cik = cikOf(code) ?: return emptyList()
        return parseFilings(getJson("$submissionsBase/submissions/CIK$cik.json"), code, cik)
    }

    /** The ticker map changes rarely; once a day keeps new listings visible. */
    private suspend fun cikOf(code: String): String? {
        val stale = ciks == null || clock.instant().isAfter(ciksLoadedAt.plus(TICKER_REFRESH.toJavaDuration()))
        if (stale) {
            ciks = parseTickers(getJson(tickersUrl))
            ciksLoadedAt = clock.instant()
        }
        return ciks?.get(code.uppercase())
    }

    private suspend fun getJson(url: String): JsonElement {
        val request =
            Request
                .Builder()
                .url(url)
                .header("User-Agent", agent)
                .build()
        return client.newCall(request).executeAsync().use { response ->
            refusal(response.code, url)?.let { throw IOException(it) }
            val text = withContext(io) { response.body.string() }
            StrictJson.parse(text) ?: throw IOException("SEC answered $url with a body that is not JSON")
        }
    }

    /** Why SEC refused, if it did. A 403 also starts the pause. */
    private fun refusal(
        status: Int,
        url: String,
    ): String? =
        when {
            status == HTTP_FORBIDDEN -> {
                pausedUntil = clock.instant().plus(SEC_BLOCK_PAUSE.toJavaDuration())
                "SEC refused this address (403); pausing SEC for an hour"
            }

            status !in HTTP_OK_RANGE -> {
                "SEC returned $status for $url"
            }

            else -> {
                null
            }
        }

    companion object {
        /** Every listed ticker with its CIK, which the submissions API is keyed by. */
        const val TICKERS_URL = "https://www.sec.gov/files/company_tickers.json"

        /** SEC's answer to an address over its fair-access limit. */
        private const val HTTP_FORBIDDEN = 403

        /** What fetch's `ok` accepts. */
        private val HTTP_OK_RANGE = 200..299

        /** How long a loaded ticker map is trusted. */
        private val TICKER_REFRESH = 1.days
    }
}
