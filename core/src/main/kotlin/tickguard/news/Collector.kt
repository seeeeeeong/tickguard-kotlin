package tickguard.news

import kotlinx.coroutines.CancellationException
import tickguard.news.NewsStore
import java.time.Instant
import java.time.InstantSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.toJavaDuration

data class NewsCollectorStats(
    val collected: Map<NewsSourceName, Int>,
    val failed: Map<NewsSourceName, Int>,
    val lastRunAt: Instant?,
)

/**
 * Polls each source for each symbol and keeps what is new.
 *
 * Only recent items are kept. A news search returns a hundred results spread
 * over months, not sorted by date; without a window the first poll would
 * file a quarter's worth of headlines as if they had just happened.
 *
 * Symbols are fetched one after another, not in parallel. The count is small
 * and SEC's fair-access rules ask for restraint, so there is nothing to gain
 * from a burst. A failure is isolated to its source and symbol: an SEC outage
 * must not stop the news poll, or one bad symbol the rest.
 */
class NewsCollector(
    private val sources: List<NewsSource>,
    private val codes: () -> List<String>,
    private val sink: NewsStore,
    private val window: Duration = 1.days,
    private val clock: InstantSource = InstantSource.system(),
    private val onError: (Exception, NewsSourceName, String) -> Unit = { _, _, _ -> },
) {
    private val collected = NewsSourceName.entries.associateWith { 0 }.toMutableMap()
    private val failed = NewsSourceName.entries.associateWith { 0 }.toMutableMap()
    private var lastRunAt: Instant? = null

    /** Returns the items stored for the first time on this run. */
    suspend fun run(): List<NewsItem> {
        val fresh = mutableListOf<NewsItem>()
        for (source in sources) {
            for (code in codes()) fresh += poll(source, code)
        }
        lastRunAt = clock.instant()
        return fresh
    }

    fun stats() = NewsCollectorStats(collected.toMap(), failed.toMap(), lastRunAt)

    /** One source for one symbol. A failure is counted and goes no further. */
    @Suppress("TooGenericExceptionCaught") // A source may fail in any way; each way is isolated the same.
    private suspend fun poll(
        source: NewsSource,
        code: String,
    ): List<NewsItem> =
        try {
            keep(source.itemsFor(code))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failed[source.name] = failed.getValue(source.name) + 1
            onError(failure, source.name, code)
            emptyList()
        }

    private suspend fun keep(items: List<NewsItem>): List<NewsItem> {
        val cutoff = clock.instant().minus(window.toJavaDuration())
        return items.filter { item ->
            val kept = item.publishedAt >= cutoff && sink.recordNews(item, clock.instant())
            if (kept) collected[item.source] = collected.getValue(item.source) + 1
            kept
        }
    }
}
