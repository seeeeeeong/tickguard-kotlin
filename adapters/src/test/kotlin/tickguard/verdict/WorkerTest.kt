package tickguard.verdict

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tickguard.news.NewsItem
import tickguard.news.NewsKey
import tickguard.news.NewsSourceName
import tickguard.store.sqlite.SqliteStore
import tickguard.verdict.MAX_VERDICT_ATTEMPTS
import java.io.IOException
import java.nio.file.Files
import java.time.Instant
import java.time.InstantSource
import java.time.temporal.ChronoUnit
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

/** 12:00 in Seoul. */
private val NOON = Instant.parse("2026-09-23T03:00:00Z")

private fun hoursBefore(hours: Long) = NOON.minus(hours, ChronoUnit.HOURS)

private fun story(
    id: String,
    publishedAt: Instant,
) = NewsItem(NewsSourceName.GOOGLE_NEWS, id, "AMZN", id, "CNBC", "https://x/$id", publishedAt)

private val verdict = Verdict(relevant = true, direction = Direction.DOWN, impact = 0.8, summary = "요약")

/** Against the real store: the budget and the retry schedule live in its tables. */
class WorkerTest {
    private val directory = Files.createTempDirectory("tickguard-verdict-")
    private val path = directory.resolve("test.db").toString()
    private var store = SqliteStore.open(path)
    private val asked = mutableListOf<String>()

    @AfterEach
    fun tearDown() =
        runTest {
            store.close()
            directory.toFile().deleteRecursively()
        }

    private fun judgeThat(answer: (String) -> Verdict) =
        object : Judge {
            override val model = "deepseek-flash"

            override suspend fun judge(item: JudgeInput): Verdict {
                asked += item.title
                return answer(item.title)
            }
        }

    private val agrees = judgeThat { verdict }

    @Test
    fun `judges the newest stories first when the budget cannot cover them all`() =
        runTest {
            store.recordNews(story("old", hoursBefore(3)), NOON)
            store.recordNews(story("new", hoursBefore(1)), NOON)
            store.recordNews(story("mid", hoursBefore(2)), NOON)

            VerdictWorker(store, agrees, dailyLimit = 2, clock = InstantSource.fixed(NOON)).run()

            assertThat(asked).containsExactly("new", "mid")
        }

    @Test
    fun `does not judge a story twice`() =
        runTest {
            store.recordNews(story("a", hoursBefore(1)), NOON)
            val worker = VerdictWorker(store, agrees, dailyLimit = 10, clock = InstantSource.fixed(NOON))

            worker.run()
            worker.run()

            assertThat(asked).hasSize(1)
            val stored = store.verdictFor(NewsKey(NewsSourceName.GOOGLE_NEWS, "a", "AMZN"))
            assertThat(stored?.verdict).isEqualTo(verdict)
            assertThat(stored?.model).isEqualTo("deepseek-flash")
        }

    @Test
    fun `keeps the daily budget across a restart, and restores it at midnight in Seoul`() =
        runTest {
            for (id in listOf("a", "b", "c")) store.recordNews(story(id, hoursBefore(1)), NOON)

            VerdictWorker(store, agrees, dailyLimit = 1, clock = InstantSource.fixed(NOON)).run()
            store.close()
            store = SqliteStore.open(path)
            // A fresh process on the same day: the budget is spent.
            VerdictWorker(store, agrees, dailyLimit = 1, clock = InstantSource.fixed(NOON)).run()
            assertThat(asked).hasSize(1)

            val nextDay = Instant.parse("2026-09-23T15:00:01Z")
            VerdictWorker(store, agrees, dailyLimit = 1, window = 2.days, clock = InstantSource.fixed(nextDay)).run()
            assertThat(asked).hasSize(2)
        }

    @Test
    fun `retries a failed story with backoff, and gives up after the limit`() =
        runTest {
            store.recordNews(story("a", hoursBefore(1)), NOON)
            var now = NOON
            val errors = mutableListOf<Exception>()
            val worker =
                VerdictWorker(
                    store,
                    judgeThat { throw IOException("DeepSeek returned 503") },
                    dailyLimit = 100,
                    clock = { now },
                    onError = { error, _ -> errors += error },
                )

            worker.run()
            assertThat(asked).hasSize(1)

            now += (retryDelay(1) - 1.milliseconds).toJavaDuration()
            worker.run()
            assertThat(asked).hasSize(1)

            for (attempt in 1 until MAX_VERDICT_ATTEMPTS + 3) {
                now += retryDelay(attempt).toJavaDuration()
                worker.run()
            }

            assertThat(asked).hasSize(MAX_VERDICT_ATTEMPTS)
            assertThat(worker.stats().failed).isEqualTo(MAX_VERDICT_ATTEMPTS)
            assertThat(errors).hasSize(MAX_VERDICT_ATTEMPTS)
        }

    @Test
    fun `passes over stories older than the window`() =
        runTest {
            store.recordNews(story("stale", hoursBefore(30)), NOON)

            VerdictWorker(store, agrees, dailyLimit = 10, clock = InstantSource.fixed(NOON)).run()

            assertThat(asked).isEmpty()
        }

    @Test
    fun `doubles the retry delay from a minute and stops at an hour`() {
        assertThat(listOf(1, 2, 3, 7, 20).map(::retryDelay))
            .containsExactly(1.minutes, 2.minutes, 4.minutes, 60.minutes, 60.minutes)
    }
}
