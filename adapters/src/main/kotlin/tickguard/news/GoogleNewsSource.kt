package tickguard.news

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.coroutines.executeAsync
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.HexFormat
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/** Google News search RSS, one query per symbol. See [parseGoogleNews] for what it yields. */
class GoogleNewsSource(
    http: OkHttpClient,
    private val baseUrl: String = "https://news.google.com",
    timeout: Duration = 10.seconds,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : NewsSource {
    override val name = NewsSourceName.GOOGLE_NEWS

    private val client = http.newBuilder().callTimeout(timeout.toJavaDuration()).build()

    override suspend fun itemsFor(code: String): List<NewsItem> {
        val url = "$baseUrl/rss/search?q=${encodeUriComponent("$code stock")}&hl=en-US&gl=US&ceid=US:en"
        return client.newCall(Request.Builder().url(url).build()).executeAsync().use { response ->
            if (!response.isSuccessful) throw IOException("Google News returned ${response.code} for $code")
            parseGoogleNews(withContext(io) { response.body.string() }, code)
        }
    }
}

/**
 * JavaScript's `encodeURIComponent`: a space is `%20`, not the `+` Java's
 * form encoding writes, and the handful of marks it leaves alone stay literal.
 */
internal fun encodeUriComponent(text: String): String =
    text.toByteArray(StandardCharsets.UTF_8).joinToString("") { byte ->
        val char = byte.toUByte().toInt().toChar()
        if (char in ASCII_ALPHANUMERIC || char in UNRESERVED) "$char" else "%${UPPER_HEX.toHexDigits(byte)}"
    }

/** Percent-escapes are written in upper case, as `encodeURIComponent` writes them. */
private val UPPER_HEX = HexFormat.of().withUpperCase()

/** Left unescaped by `encodeURIComponent`, with [UNRESERVED]. Only ASCII: [Char.isLetterOrDigit] would pass `é`. */
private val ASCII_ALPHANUMERIC = ('a'..'z') + ('A'..'Z') + ('0'..'9')

/** Left unescaped by `encodeURIComponent`, besides ASCII letters and digits. */
private const val UNRESERVED = "-_.!~*'()"
