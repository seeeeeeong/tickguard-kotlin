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
    val waitMs = app.pump.maxWait().inWholeMilliseconds
    return listOf(
        StatusPanel("startup", app.startup, ok = app.startup == "ready"),
        StatusPanel("stream", uptime(app.startedAt, now), ok = opens > 0),
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
        StatusPanel("sla watching", "${s.sla.watched} (${s.sla.open} open)"),
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

/** The numbers the original exported, under its names. Each is read from its source when scraped. */
internal fun registerMetrics(
    app: Tickguard,
    metrics: Metrics,
    clock: java.time.InstantSource,
) {
    metrics.gauge("tickguard.uptime.seconds", "Process uptime.") {
        (clock.millis() - app.startedAt.toEpochMilli()) / MILLIS_PER_SECOND
    }
    metrics.counter("tickguard.connections", "Socket opens.") { app.counters.opens.get() }
    metrics.counter("tickguard.ticks", "Decoded trades.") { app.counters.ticks.get() }
    metrics.counter("tickguard.decode.dropped", "Undecodable frames.") { app.counters.decodeDropped.get() }
    metrics.gauge("tickguard.batch.ms.max", "Longest batch hold.") { app.pump.maxBatch().inWholeMilliseconds }
    metrics.gauge("tickguard.queue.wait.ms.max", "Longest wait between the socket and a handler.") {
        app.pump.maxWait().inWholeMilliseconds
    }
    metrics.gauge("tickguard.drift.worst", "Worst WS vs REST drift.") { app.worstDrift }
    metrics.counter("tickguard.signals", "Signals raised.") { app.counters.signals.get() }
    metrics.counter("tickguard.incidents", "SLA incidents.") { app.counters.incidents.get() }
    metrics.counter("tickguard.incidents.inhibited", "SLA incidents held back during an IP block.") {
        app.snapshot.incidents.inhibited
    }
    metrics.gauge("tickguard.ip.blocked", "Toss refusing our source IP.") {
        if (app.link.blockedSince ==
            null
        ) {
            0
        } else {
            1
        }
    }
    metrics.fromStats("tickguard.inbox.queued", "Frames waiting.", counter = false) {
        app.snapshot.inbox.queued
            .mapKeys { it.key.name.lowercase() }
    }
    metrics.fromStats("tickguard.rules", "Rule outcomes.", counter = true) {
        with(app.snapshot.rules) {
            mapOf(
                "fired" to fired,
                "suppressed" to suppressed,
                "pending" to pending,
                "errors" to errors,
            )
        }
    }
    metrics.fromStats(
        "tickguard.rules.declined",
        "Evaluations a rule could not answer.",
        counter = true,
        labelName = "reason",
    ) {
        app.snapshot.rules.declined
            .mapKeys { it.key.wire }
    }
    metrics.fromStats("tickguard.notify", "Delivery outcomes.", counter = true) {
        with(app.notifier.stats()) { mapOf("delivered" to delivered, "retried" to retried, "abandoned" to abandoned) }
    }
    registerNewsMetrics(app, metrics)
    registerRecordingMetrics(app, metrics)
    metrics.fromStats("tickguard.ratelimit", "REST limiter.", counter = true) {
        with(app.snapshot.limiter) { mapOf("waits" to waits, "totalWaitMs" to totalWait.inWholeMilliseconds) }
    }
}

private fun registerRecordingMetrics(
    app: Tickguard,
    metrics: Metrics,
) {
    metrics.counter("tickguard.ticks.recorded", "Trades stored.") { app.snapshot.ticks.written }
    metrics.gauge("tickguard.ticks.queued", "Trades waiting to be stored.") { app.snapshot.ticks.queued }
    metrics.counter("tickguard.ticks.dropped", "Trades dropped from a full queue.") { app.snapshot.ticks.dropped }
    metrics.counter("tickguard.ticks.record.failed", "Trades not stored.") { app.snapshot.ticks.failed }
    metrics.gauge("tickguard.fallback.active", "REST standing in for the stream.") {
        if (app.snapshot.fallback.active) 1 else 0
    }
    metrics.fromStats("tickguard.fallback", "REST fallback polls.", counter = true) {
        with(app.snapshot.fallback) { mapOf("polls" to polls, "quotes" to quotes, "failures" to failures) }
    }
}

private fun registerNewsMetrics(
    app: Tickguard,
    metrics: Metrics,
) {
    metrics.fromStats("tickguard.news.collected", "New stories stored.", counter = true) {
        app.snapshot.news.collected
            .mapKeys { it.key.wire }
    }
    metrics.fromStats("tickguard.news.failed", "Failed news polls.", counter = true) {
        app.snapshot.news.failed
            .mapKeys { it.key.wire }
    }
    metrics.fromStats("tickguard.verdicts", "Model verdicts.", counter = true) {
        val stats = app.snapshot.verdicts
        mapOf("judged" to (stats?.judged ?: 0), "failed" to (stats?.failed ?: 0))
    }
    metrics.gauge("tickguard.model.calls.today", "Model calls since KST midnight.") {
        app.snapshot.verdicts?.usedToday ?: 0
    }
}

internal fun uptime(
    since: Instant,
    now: Instant,
): String {
    val seconds = Math.round((now.toEpochMilli() - since.toEpochMilli()) / MILLIS_PER_SECOND)
    val hours = seconds / SECONDS_PER_HOUR
    return when {
        seconds < SECONDS_PER_MINUTE -> "up ${seconds}s"
        seconds < SECONDS_PER_HOUR -> "up ${seconds / SECONDS_PER_MINUTE}m"
        hours < HOURS_PER_DAY -> "up ${hours}h"
        else -> "up ${hours / HOURS_PER_DAY}d ${hours % HOURS_PER_DAY}h"
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
