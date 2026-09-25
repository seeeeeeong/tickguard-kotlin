package tickguard.runner

import tickguard.news.NewsSourceName
import tickguard.observability.Metrics
import tickguard.observability.StatusPanel
import tickguard.pipeline.Lane
import tickguard.text.toFixed
import java.time.Instant
import java.util.Locale
import kotlin.math.ceil

/** The status page, from the latest snapshot and the counters. First line is what start() is waiting on. */
internal fun statusPanels(
    app: Tickguard,
    now: Instant,
): List<StatusPanel> {
    val s = app.snapshot
    val opens = app.counters.opens.get()
    val ticks = app.counters.ticks.get()
    val decodeDropped = app.counters.decodeDropped.get()
    val blockedSince = app.link.blockedSince
    val connectedSince = app.link.connectedSince
    val waitMs = app.pump.maxWait().inWholeMilliseconds
    return listOf(
        StatusPanel("startup", app.startup, ok = app.startup == "ready"),
        StatusPanel("process", uptime(app.startedAt, now)),
        streamPanel(connectedSince, blockedSince, now),
        StatusPanel(
            "network",
            if (blockedSince == null) {
                "ok · ${app.link.publicIp}"
            } else {
                "blocked ${Math.round(
                    (now.toEpochMilli() - blockedSince.toEpochMilli()) / MILLIS_PER_MINUTE,
                )}m · ${app.link.publicIp}"
            },
            ok = blockedSince == null,
        ),
        StatusPanel("connections", "$opens", ok = opens == 1L),
        StatusPanel("ticks", "%,d".format(Locale.US, ticks), ok = ticks > 0),
        with(s.ticks) {
            StatusPanel(
                "recorded",
                "${"%,d".format(Locale.US, written)} · $failed failed · $dropped dropped",
                ok = failed + dropped == 0,
            )
        },
        StatusPanel("decode dropped", "$decodeDropped", ok = decodeDropped == 0L),
        ordersPanel(app.counters),
        StatusPanel("trading", app.sleeves.describe(), ok = !app.sleeves.halted()),
        StatusPanel(
            "queue",
            "${s.inbox.queued.getValue(Lane.QUOTES)} / max ${s.inbox.maxQueued.getValue(Lane.QUOTES)}",
        ),
        // The order channel is closed after 2s of stalled consumption; a quarter of that is the budget.
        StatusPanel("queue wait max", "${waitMs}ms / 2000ms", ok = waitMs < QUEUE_WAIT_BUDGET_MS),
        fallbackPanel(app, s, now),
        StatusPanel(
            "ws↔rest drift",
            "${(app.worstDrift * PERCENT).toFixed(DRIFT_PLACES)}%",
            ok =
                app.worstDrift < DRIFT_TOLERANCE,
        ),
        StatusPanel("subscribed", "${s.topics.desired.size} topics"),
        StatusPanel("rejected", "${s.topics.rejected.size}", ok = s.topics.rejected.isEmpty()),
        StatusPanel("signals", "${s.rules.fired} fired · ${s.rules.suppressed} suppressed"),
        newsPanel(app, s, now),
        verdictPanel(s),
        StatusPanel("notify failed", "${app.notifier.stats().abandoned}", ok = app.notifier.stats().abandoned == 0),
        // Symbols whose market is in session, not open incidents, which "(3 open)" read as.
        StatusPanel("sla watching", "${s.sla.watched} · ${s.sla.open} in session"),
    )
}

/**
 * The socket, not the process. This used to show the process's uptime, which
 * read "up 13m" while Toss refused our address and no socket had ever opened.
 */
internal fun streamPanel(
    connectedSince: Instant?,
    blockedSince: Instant?,
    now: Instant,
) = StatusPanel(
    "stream",
    when {
        connectedSince != null -> "connected ${age(connectedSince, now)}"
        blockedSince != null -> "down · IP blocked"
        else -> "down · reconnecting"
    },
    ok = connectedSince != null,
)

private fun ordersPanel(counters: Counters): StatusPanel =
    with(counters) {
        val unreadable = orderUnreadable.get()
        val failed = orderResyncFailures.get()
        StatusPanel(
            "orders",
            "${orderEvents.get()} events · $unreadable unreadable · ${orderResyncs.get()} resyncs, $failed failed",
            ok = unreadable == 0L && failed == 0L,
        )
    }

private fun fallbackPanel(
    app: Tickguard,
    s: Snapshot,
    now: Instant,
): StatusPanel {
    val silent = Math.round((now.toEpochMilli() - app.lastQuoteAt.toEpochMilli()) / MILLIS_PER_SECOND)
    return StatusPanel(
        "fallback",
        if (s.fallback.active) {
            "REST polling ${s.fallback.silentSymbols} symbols · stream last quote ${silent}s ago · ${s.fallback.polls} polls"
        } else {
            "idle"
        },
        ok = !s.fallback.active && s.fallback.failures == 0,
    )
}

private fun newsPanel(
    app: Tickguard,
    s: Snapshot,
    now: Instant,
): StatusPanel {
    val collected = s.news.collected
    val paused = s.secPausedUntil
    val sec =
        when {
            app.sec == null -> "sec off"

            paused != null -> "sec paused ${ceil(
                (paused.toEpochMilli() - now.toEpochMilli()) / MILLIS_PER_MINUTE,
            ).toLong()}m (403)"

            else -> "sec ${collected[NewsSourceName.SEC]}"
        }
    val lastRunAt = s.news.lastRunAt
    val age =
        if (lastRunAt ==
            null
        ) {
            "아직 없음"
        } else {
            "${Math.round((now.toEpochMilli() - lastRunAt.toEpochMilli()) / MILLIS_PER_MINUTE)}m ago"
        }
    return StatusPanel(
        "news",
        "google ${collected[NewsSourceName.GOOGLE_NEWS]} · $sec · $age",
        ok =
            s.news.failed.values
                .sum() == 0,
    )
}

private fun verdictPanel(s: Snapshot): StatusPanel {
    val stats = s.verdicts ?: return StatusPanel("verdicts", "off (no LLM key)")
    return StatusPanel(
        "verdicts",
        "judged ${stats.judged} · failed ${stats.failed} · today ${stats.usedToday}/${stats.dailyLimit}",
        ok = stats.usedToday < stats.dailyLimit,
    )
}

internal fun uptime(
    since: Instant,
    now: Instant,
): String = "up ${age(since, now)}"

/** `42s`, `13m`, `5h`, `2d 3h`: coarse on purpose, for a page read at a glance. */
internal fun age(
    since: Instant,
    now: Instant,
): String {
    val seconds = Math.round((now.toEpochMilli() - since.toEpochMilli()) / MILLIS_PER_SECOND)
    val hours = seconds / SECONDS_PER_HOUR
    return when {
        seconds < SECONDS_PER_MINUTE -> "${seconds}s"
        seconds < SECONDS_PER_HOUR -> "${seconds / SECONDS_PER_MINUTE}m"
        hours < HOURS_PER_DAY -> "${hours}h"
        else -> "${hours / HOURS_PER_DAY}d ${hours % HOURS_PER_DAY}h"
    }
}

/** The pump's wait budget: a quarter of the order channel's two-second stall limit. */
private const val QUEUE_WAIT_BUDGET_MS = 500

/** Drift under half a percent is the stream agreeing with REST. */
private const val DRIFT_TOLERANCE = 0.005

/** Drift is shown to a thousandth of a percent, well under the tolerance. */
private const val DRIFT_PLACES = 3

/** A ratio times this is a percent. */
private const val PERCENT = 100

/** Unit arithmetic for the uptime line. */
private const val MILLIS_PER_SECOND = 1_000.0

/** See [MILLIS_PER_SECOND]. */
private const val MILLIS_PER_MINUTE = 60_000.0

/** See [MILLIS_PER_SECOND]. */
private const val SECONDS_PER_MINUTE = 60

/** See [MILLIS_PER_SECOND]. */
private const val SECONDS_PER_HOUR = 3_600

/** See [MILLIS_PER_SECOND]. */
private const val HOURS_PER_DAY = 24
