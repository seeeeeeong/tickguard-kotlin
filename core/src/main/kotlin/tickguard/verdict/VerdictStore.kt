package tickguard.verdict

import tickguard.news.NewsKey
import tickguard.news.StoredNews
import java.time.Instant

/** What the verdict worker needs: stories to judge, verdicts made, and the day's call budget. */
interface VerdictStore {
    /**
     * Stories published at or after `since` that still need a verdict: never
     * tried, or failed with a retry now due. Newest first, so a tight budget
     * spends itself on what is still news.
     */
    suspend fun pendingVerdicts(
        since: Instant,
        now: Instant,
        limit: Int,
    ): List<PendingStory>

    suspend fun recordVerdict(
        story: NewsKey,
        verdict: Verdict,
        model: String,
        at: Instant,
    )

    suspend fun recordVerdictFailure(
        story: NewsKey,
        error: String,
        attempts: Int,
        retryAt: Instant,
    )

    suspend fun verdictFor(story: NewsKey): StoredVerdict?

    suspend fun recordModelCall(at: Instant)

    suspend fun modelCallsSince(since: Instant): Int

    suspend fun pruneVerdicts(olderThan: Instant): Int
}

/** A story that fails this often is left alone; its headline is the problem. */
const val MAX_VERDICT_ATTEMPTS = 5

data class PendingStory(
    val news: StoredNews,
    val attempts: Int,
)

data class StoredVerdict(
    val verdict: Verdict,
    val model: String,
    val judgedAt: Instant,
)
