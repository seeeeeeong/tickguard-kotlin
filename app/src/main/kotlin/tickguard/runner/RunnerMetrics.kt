package tickguard.runner

import tickguard.observability.Metrics

/** The numbers the original exported, under its names. Each is read from its source when scraped. */
internal fun registerMetrics(
    app: Tickguard,
    metrics: Metrics,
    clock: java.time.InstantSource,
) {
    metrics.gauge("tickguard.uptime.seconds", "Process uptime.") {
        (clock.millis() - app.startedAt.toEpochMilli()) / MILLIS_PER_SECOND_AS_DOUBLE
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
    registerOrderMetrics(app, metrics)
    metrics.fromStats("tickguard.ratelimit", "REST limiter.", counter = true) {
        with(app.snapshot.limiter) { mapOf("waits" to waits, "totalWaitMs" to totalWait.inWholeMilliseconds) }
    }
}

private fun registerOrderMetrics(
    app: Tickguard,
    metrics: Metrics,
) {
    metrics.counter("tickguard.orders.events", "Order events read.") { app.counters.orderEvents.get() }
    metrics.counter("tickguard.orders.unreadable", "Order events that could not be read.") {
        app.counters.orderUnreadable.get()
    }
    metrics.counter("tickguard.orders.resyncs", "Order resyncs after a connection.") { app.counters.orderResyncs.get() }
    metrics.fromStats("tickguard.orders.recorded", "Order changes by what recording them did.", counter = true) {
        with(app.orders.recorder.stats()) {
            mapOf("new" to newStates, "stale" to stale, "repeat" to repeats, "failed" to failures)
        }
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

/** Uptime is exported in seconds, as the original's gauge was. */
private const val MILLIS_PER_SECOND_AS_DOUBLE = 1_000.0
