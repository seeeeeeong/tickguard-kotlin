package tickguard.news

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tickguard.news.NewsStore
import tickguard.news.StoredNews
import java.io.IOException
import java.time.Instant
import java.time.InstantSource
import java.time.temporal.ChronoUnit

private val NOW = Instant.parse("2026-09-23T12:00:00Z")

private fun story(
    id: String,
    publishedAt: Instant = NOW.minus(1, ChronoUnit.HOURS),
    code: String = "AMZN",
) = NewsItem(NewsSourceName.GOOGLE_NEWS, id, code, id, "CNBC", "https://x/$id", publishedAt)

private class MemorySink : NewsStore {
    private val seen = mutableSetOf<String>()

    override suspend fun recordNews(
        item: NewsItem,
        seenAt: Instant,
    ) = seen.add("${item.source}|${item.id}|${item.code}")

    override suspend fun newsFor(
        code: String,
        since: Instant,
    ) = emptyList<StoredNews>()

    override suspend fun pruneNews(olderThan: Instant) = 0
}

private fun sourceOf(
    name: NewsSourceName,
    itemsFor: suspend (String) -> List<NewsItem>,
) = object : NewsSource {
    override val name = name

    override suspend fun itemsFor(code: String) = itemsFor(code)
}

class CollectorTest {
    private val clock = InstantSource.fixed(NOW)

    @Test
    fun `keeps only recent stories, since a search returns months of them`() =
        runTest {
            val collector =
                NewsCollector(
                    sources =
                        listOf(
                            sourceOf(NewsSourceName.GOOGLE_NEWS) {
                                listOf(story("today"), story("last-quarter", NOW.minus(90, ChronoUnit.DAYS)))
                            },
                        ),
                    codes = { listOf("AMZN") },
                    sink = MemorySink(),
                    clock = clock,
                )

            assertThat(collector.run().map { it.id }).containsExactly("today")
        }

    @Test
    fun `returns a story once, however many polls see it`() =
        runTest {
            val collector =
                NewsCollector(
                    sources = listOf(sourceOf(NewsSourceName.GOOGLE_NEWS) { listOf(story("a")) }),
                    codes = { listOf("AMZN") },
                    sink = MemorySink(),
                    clock = clock,
                )

            assertThat(collector.run()).hasSize(1)
            assertThat(collector.run()).isEmpty()
            assertThat(collector.stats().collected[NewsSourceName.GOOGLE_NEWS]).isEqualTo(1)
        }

    @Test
    fun `isolates a failing source, so an SEC outage does not stop the news`() =
        runTest {
            val errors = mutableListOf<Triple<Exception, NewsSourceName, String>>()
            val collector =
                NewsCollector(
                    sources =
                        listOf(
                            sourceOf(NewsSourceName.SEC) { throw IOException("SEC returned 503") },
                            sourceOf(NewsSourceName.GOOGLE_NEWS) { code -> listOf(story("n-$code", code = code)) },
                        ),
                    codes = { listOf("AMZN", "GOOGL") },
                    sink = MemorySink(),
                    clock = clock,
                    onError = { error, source, code -> errors += Triple(error, source, code) },
                )

            val fresh = collector.run()

            assertThat(fresh.map { it.code }).containsExactly("AMZN", "GOOGL")
            assertThat(collector.stats().failed[NewsSourceName.SEC]).isEqualTo(2)
            assertThat(errors.first().second).isEqualTo(NewsSourceName.SEC)
            assertThat(errors.first().third).isEqualTo("AMZN")
        }
}
