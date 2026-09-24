package tickguard.verdict

import kotlinx.coroutines.CancellationException
import tickguard.news.StoredNews
import tickguard.time.kstDayStart
import tickguard.verdict.PendingStory
import tickguard.verdict.VerdictStore
import java.time.InstantSource
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

data class VerdictWorkerStats(
    val judged: Int,
    val failed: Int,
    val usedToday: Int,
    val dailyLimit: Int,
)

/** The first retry waits a minute. */
private val RETRY_BASE = 1.minutes

/** Doubling stops at an hour: a model down that long is waited out, not hammered. */
private val RETRY_CAP = 1.hours

fun retryDelay(attempts: Int): Duration {
    val doubled = RETRY_BASE.inWholeMilliseconds * (1L shl (attempts - 1).coerceIn(0, MAX_SHIFT))
    return min(RETRY_CAP.inWholeMilliseconds, doubled).milliseconds
}

/** Enough doublings to pass the cap; more would overflow a Long. */
private const val MAX_SHIFT = 30

/**
 * Works through stories that have no verdict yet.
 *
 * One call at a time. Each takes about a second and a half, the batch is
 * small, and sequential calls make the daily budget exact: it is checked
 * before every call, not estimated for a burst.
 *
 * The budget is counted from stored calls since midnight in Seoul, not held
 * in memory, so restarting the process does not hand it a fresh day's worth.
 * A news storm then costs at most the budget, never the bill of the storm.
 *
 * A failed call is retried later with backoff, up to a limit enforced by the
 * store. A model outage delays verdicts; it never drops a story.
 */
class VerdictWorker(
    private val store: VerdictStore,
    private val judge: Judge,
    private val dailyLimit: Int,
    /** Bounds one run, so a backlog is worked off across polls rather than in one. */
    private val perRun: Int = DEFAULT_PER_RUN,
    /** Stories older than this are not worth a call. Matches the news window. */
    private val window: Duration = 1.days,
    private val clock: InstantSource = InstantSource.system(),
    private val onVerdict: (StoredNews, Verdict) -> Unit = { _, _ -> },
    private val onError: (Exception, StoredNews) -> Unit = { _, _ -> },
) {
    private var judged = 0
    private var failed = 0

    // Read from the store at the start of every run, so the budget is the
    // stored one; counted up in between so the status page stays current
    // without a query of its own.
    private var usedToday = 0

    suspend fun run() {
        usedToday = store.modelCallsSince(kstDayStart(clock.instant()))
        val budget = min(perRun, dailyLimit - usedToday)
        if (budget <= 0) return

        val now = clock.instant()
        for (story in store.pendingVerdicts(now.minus(window.toJavaDuration()), now, budget)) judgeOne(story)
    }

    fun stats() = VerdictWorkerStats(judged, failed, usedToday, dailyLimit)

    @Suppress("TooGenericExceptionCaught") // Any failure of a call is retried later the same way.
    private suspend fun judgeOne(story: PendingStory) {
        store.recordModelCall(clock.instant())
        usedToday += 1
        val news = story.news
        try {
            val verdict =
                judge.judge(
                    JudgeInput(news.item.code, news.item.title, news.item.publisher, news.item.source.wire),
                )
            store.recordVerdict(news.key, verdict, judge.model, clock.instant())
            judged += 1
            onVerdict(news, verdict)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val attempts = story.attempts + 1
            store.recordVerdictFailure(
                news.key,
                failure.message.orEmpty(),
                attempts,
                clock.instant().plus(retryDelay(attempts).toJavaDuration()),
            )
            failed += 1
            onError(failure, news)
        }
    }

    private companion object {
        /** About twenty seconds of calls a run. */
        const val DEFAULT_PER_RUN = 30
    }
}
