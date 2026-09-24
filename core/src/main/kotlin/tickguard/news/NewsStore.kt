package tickguard.news

import java.time.Instant

/** What the news collector keeps, so a re-poll is not news. */
interface NewsStore {
    /** False when this story was already stored for this symbol. */
    suspend fun recordNews(
        item: NewsItem,
        seenAt: Instant,
    ): Boolean

    /** One symbol's stories published at or after `since`, newest first. */
    suspend fun newsFor(
        code: String,
        since: Instant,
    ): List<StoredNews>

    suspend fun pruneNews(olderThan: Instant): Int
}

/** Identifies a story: one headline, from one source, about one symbol. */
data class NewsKey(
    val source: NewsSourceName,
    val id: String,
    val code: String,
)

data class StoredNews(
    val item: NewsItem,
    val seenAt: Instant,
) {
    val key get() = NewsKey(item.source, item.id, item.code)
}
