package tickguard.store

import tickguard.news.NewsItem
import tickguard.news.NewsSourceName
import tickguard.rules.CooldownStore
import tickguard.subscribe.RejectionStore
import tickguard.verdict.Verdict
import java.time.Instant

/**
 * The state that must outlive a restart.
 *
 * Everything the pipeline holds is in memory, which is fine for a price
 * window and wrong for these:
 *
 * - **Cooldowns.** A restart with empty cooldowns re-fires every rule that
 *   still holds, so a deploy during a drawdown sends the same alert again.
 * - **Rejected topics.** The server rejects them again on every declaration,
 *   so forgetting them means re-learning the same failures on every restart.
 * - **Ticks.** A backtest can only replay what was kept, and history only
 *   starts accumulating on the day recording does.
 * - **News.** What has been seen, so a re-poll is not news, and the gap
 *   between publication and discovery can be measured per source.
 * - **Verdicts and model calls.** A story judged is not judged again, and a
 *   daily call budget that restarted with the process would not be one.
 *
 * Every method suspends. The SQLite adapter answers in-process, but a database
 * across a network cannot, and the interface is the one both must honour;
 * callers on a hot path (the rule engine's cooldowns) read once at start and
 * keep the answer in memory instead of awaiting per tick.
 */
interface Store :
    CooldownStore,
    RejectionStore,
    TickStore,
    NewsStore,
    VerdictStore {
    /** For measuring how often a rule was right, once outcomes are known. Newest first. */
    suspend fun recentSignals(limit: Int): List<StoredSignal>

    suspend fun close()
}

/** What the tick writer records and a backtest replays. */
interface TickStore {
    /** One batch, written together or not at all. */
    suspend fun recordTicks(ticks: List<TickRow>)

    /** One symbol's ticks with `from <= tradedAt < to`, oldest first. */
    suspend fun ticksBetween(
        code: String,
        from: Instant,
        to: Instant,
    ): List<TickRow>

    /** Ticks traded before the cutoff. Returns how many went. */
    suspend fun pruneTicks(olderThan: Instant): Int
}

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

data class StoredSignal(
    val key: String,
    val ruleId: String,
    val code: String,
    val firedAt: Instant,
)

/**
 * Price and volume stay strings, exactly as the server sent them, for the same
 * reason they arrive that way: a REAL column would round them on the way in.
 */
data class TickRow(
    val type: String,
    val code: String,
    val price: String,
    val volume: String,
    val currency: String,
    /**
     * Exchange time, from the payload. What a replay orders by. It has
     * one-second resolution on the wire — a live run recorded several trades
     * sharing one stamp — so within a second, order is arrival order, which is
     * what the table's rowid keeps.
     */
    val tradedAt: Instant,
    /** Our clock at decode. The gap to `tradedAt` is the feed's latency. */
    val receivedAt: Instant,
)

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

data class PendingStory(
    val news: StoredNews,
    val attempts: Int,
)

data class StoredVerdict(
    val verdict: Verdict,
    val model: String,
    val judgedAt: Instant,
)
