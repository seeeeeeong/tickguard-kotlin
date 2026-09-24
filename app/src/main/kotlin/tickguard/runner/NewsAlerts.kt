package tickguard.runner

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import tickguard.news.StoredNews
import tickguard.rules.Signal
import tickguard.subscribe.Topic
import tickguard.text.toFixed
import tickguard.verdict.Direction
import tickguard.verdict.Verdict
import java.time.Duration
import java.time.InstantSource

/**
 * A story alone becomes an alert only when it is relevant, weighty and
 * fresh. Fresh, because every story from the last day is judged, and the
 * first run after a start would otherwise send yesterday's news as if it
 * had just broken.
 */
internal class NewsAlerts(
    private val minImpact: Double,
    private val clock: InstantSource,
    private val report: (Signal) -> Unit,
) {
    fun alert(
        story: StoredNews,
        verdict: Verdict,
    ) {
        val item = story.item
        val arrow =
            when (verdict.direction) {
                Direction.UP -> "▲"
                Direction.DOWN -> "▼"
                Direction.NEUTRAL -> "·"
            }
        val impact = verdict.impact.toFixed(2)
        log.info(
            "verdict {} {}{} {}: {}",
            item.code,
            arrow,
            impact,
            if (verdict.relevant) "relevant" else "filler",
            verdict.summary,
        )
        if (!verdict.relevant || verdict.impact < minImpact) return
        val now = clock.instant()
        if (Duration.between(item.publishedAt, now) > MAX_AGE) return
        report(
            Signal(
                ruleId = "news",
                code = item.code,
                title = "${item.code} $arrow ${verdict.summary}",
                detail = "${item.publisher} · 영향 $impact\n${item.title}",
                firedAt = now,
            ),
        )
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(NewsAlerts::class.java)

        /** Older stories are judged but not alerted on. */
        val MAX_AGE: Duration = Duration.ofHours(2)
    }
}

/** Both news sources cover US listings; KR needs DART, which is not wired yet. */
internal fun usCodes(
    markets: Map<String, String>,
    extraSymbols: List<Topic>,
): List<String> {
    val held = markets.filterValues { it == "trade:us" }.keys
    val extra = extraSymbols.filter { it.type == "trade:us" }.map { it.code }
    return (held + extra).distinct()
}
