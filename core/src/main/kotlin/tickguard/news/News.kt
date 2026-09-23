package tickguard.news

import java.time.Instant

/** The two sources, named as they are stored. */
enum class NewsSourceName(
    val wire: String,
) {
    SEC("sec"),
    GOOGLE_NEWS("google-news"),
    ;

    companion object {
        fun fromWire(wire: String): NewsSourceName =
            entries.firstOrNull { it.wire == wire } ?: throw IllegalArgumentException("Unknown news source: $wire")
    }
}

/**
 * What a news source produces: a headline tied to one symbol.
 *
 * Headlines only, never article bodies. Bodies are behind paywalls, bot
 * checks and terms that differ per site, and a headline is enough for the
 * question asked of it later: does this plausibly move this stock.
 */
data class NewsItem(
    val source: NewsSourceName,
    /** Stable per story within a source, so a re-poll is recognised as seen. */
    val id: String,
    val code: String,
    val title: String,
    val publisher: String,
    val url: String,
    val publishedAt: Instant,
)

interface NewsSource {
    val name: NewsSourceName

    suspend fun itemsFor(code: String): List<NewsItem>
}
