package tickguard.runner

import tickguard.holdings.HoldingsStats
import tickguard.news.NewsCollectorStats
import tickguard.news.NewsSourceName
import tickguard.pipeline.InboxStats
import tickguard.pipeline.Lane
import tickguard.rest.RateLimiterStats
import tickguard.rules.RuleEngineStats
import tickguard.sla.ReporterStats
import tickguard.sla.SlaStats
import tickguard.subscribe.CoordinatorSnapshot
import tickguard.verdict.VerdictWorkerStats
import java.time.Instant
import kotlin.time.Duration

/**
 * The engine's state as other threads may read it.
 *
 * The status page and the metrics scrape run on web threads, while rules,
 * subscriptions and the SLA watcher keep their state in plain maps confined to
 * the engine. Reading those maps from a web thread would race the engine and
 * can throw mid-iteration, so the engine publishes this every few seconds and
 * readers take the latest one whole.
 */
internal data class Snapshot(
    val rules: RuleEngineStats,
    val topics: CoordinatorSnapshot,
    val sla: SlaStats,
    val incidents: ReporterStats,
    val holdings: HoldingsStats,
    val limiter: RateLimiterStats,
    val inbox: InboxStats,
    val news: NewsCollectorStats,
    /** Null when no LLM key is configured. */
    val verdicts: VerdictWorkerStats?,
    val secPausedUntil: Instant?,
) {
    companion object {
        /** What readers see before the engine has published its first snapshot. */
        val EMPTY =
            Snapshot(
                rules = RuleEngineStats(0, 0, 0, 0, 0, 0),
                topics = CoordinatorSnapshot(emptyList(), emptyList(), 0),
                sla = SlaStats(0, 0),
                incidents = ReporterStats(0, 0, 0),
                holdings = HoldingsStats(0, 0, 0),
                limiter = RateLimiterStats(0, Duration.ZERO),
                inbox =
                    InboxStats(
                        Lane.entries.associateWith {
                            0
                        },
                        Lane.entries.associateWith { 0 },
                        0,
                        Lane.entries.associateWith { 0 },
                    ),
                news =
                    NewsCollectorStats(
                        NewsSourceName.entries.associateWith { 0 },
                        NewsSourceName.entries.associateWith { 0 },
                        null,
                    ),
                verdicts = null,
                secPausedUntil = null,
            )

        /** Taken on the engine. */
        suspend fun of(app: Tickguard) =
            Snapshot(
                rules = app.rules.stats(),
                topics = app.topics.snapshot(),
                sla = app.sla.stats(),
                incidents = app.incidents.stats(),
                holdings = app.holdings.stats(),
                limiter = app.rest.limiter.stats(),
                inbox = app.inbox.stats(),
                news = app.news.stats(),
                verdicts = app.verdicts?.stats(),
                secPausedUntil = app.sec?.pausedUntil(),
            )
    }
}
