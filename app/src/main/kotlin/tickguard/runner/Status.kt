package tickguard.runner

import tickguard.observability.Metrics
import tickguard.observability.StatusPanel
import tickguard.pipeline.Lane
import java.time.Instant
import java.util.Locale

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
        StatusPanel("decode dropped", "$decodeDropped", ok = decodeDropped == 0L),
        StatusPanel(
            "queue",
            "${s.inbox.queued.getValue(Lane.QUOTES)} / max ${s.inbox.maxQueued.getValue(Lane.QUOTES)}",
        ),
        // The order channel is closed after 2s of stalled consumption; a quarter of that is the budget.
        StatusPanel("queue wait max", "${waitMs}ms / 2000ms", ok = waitMs < QUEUE_WAIT_BUDGET_MS),
        StatusPanel(
            "ws↔rest drift",
            "%.3f%%".format(Locale.ROOT, app.worstDrift * PERCENT),
            ok =
                app.worstDrift < DRIFT_TOLERANCE,
        ),
        StatusPanel("subscribed", "${s.topics.desired.size} topics"),
        StatusPanel("rejected", "${s.topics.rejected.size}", ok = s.topics.rejected.isEmpty()),
        StatusPanel("signals", "${s.rules.fired} fired · ${s.rules.suppressed} suppressed"),
        StatusPanel("notify failed", "${app.notifier.stats().abandoned}", ok = app.notifier.stats().abandoned == 0),
        StatusPanel("sla watching", "${s.sla.watched} (${s.sla.open} open)"),
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
    metrics.fromStats("tickguard.notify", "Delivery outcomes.", counter = true) {
        with(app.notifier.stats()) { mapOf("delivered" to delivered, "retried" to retried, "abandoned" to abandoned) }
    }
    metrics.fromStats("tickguard.ratelimit", "REST limiter.", counter = true) {
        with(app.snapshot.limiter) { mapOf("waits" to waits, "totalWaitMs" to totalWait.inWholeMilliseconds) }
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
