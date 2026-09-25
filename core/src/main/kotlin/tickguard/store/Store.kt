package tickguard.store

import tickguard.news.NewsStore
import tickguard.orders.OrderStore
import tickguard.rules.CooldownStore
import tickguard.subscribe.RejectionStore
import tickguard.verdict.VerdictStore
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
 * - **Orders.** What the account actually did. The stream never redelivers
 *   an event, so what was heard is kept rather than asked for again.
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
    VerdictStore,
    OrderStore {
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
