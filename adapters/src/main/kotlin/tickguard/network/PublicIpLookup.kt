package tickguard.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.coroutines.executeAsync
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Asked when a connection is refused, not on a schedule. A changed IP breaks
 * the NAT mapping of every open connection anyway, so the refusal on the
 * reconnect that follows is the first and surest sign; polling could only
 * learn it later.
 */
class PublicIpLookup(
    http: OkHttpClient,
    private val sources: List<String> = PUBLIC_IP_SOURCES,
    timeout: Duration = 5.seconds,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val client = http.newBuilder().callTimeout(timeout.toJavaDuration()).build()

    suspend fun lookup(): PublicIp {
        val results = coroutineScope { sources.map { async { ask(it) } }.awaitAll() }
        val valid = results.filterNotNull()
        val answers = valid.distinct()
        return PublicIp(ip = answers.singleOrNull(), answers = answers, failed = results.size - valid.size)
    }

    /** A source that fails in any way is one that did not answer. */
    private suspend fun ask(url: String): String? =
        try {
            client.newCall(Request.Builder().url(url).build()).executeAsync().use { response ->
                if (!response.isSuccessful) return null
                withContext(io) { response.body.string() }.trim().takeIf(::isIpv4)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    companion object {
        /** Both are IPv4-only hosts, so neither can answer with an address Toss never sees. */
        val PUBLIC_IP_SOURCES = listOf("https://api.ipify.org", "https://checkip.amazonaws.com")
    }
}
