package tickguard.runner

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import tickguard.network.reasonOf
import java.time.LocalTime
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Starts the app after the web server, stops it before.
 *
 * The status page comes up first, so a server waiting on its network says so
 * instead of looking dead; Spring starts its web server in an earlier phase
 * than this one. [start] returns at once: the app may wait minutes for a
 * network, and a start that blocked would hold the context's startup lock,
 * which a shutdown signal then waits on — the original took care that a
 * signal during that wait still drained.
 *
 * Shutdown order is the part worth reading. The app finishes what it holds —
 * a group waiting out its window, a Slack delivery in flight — before the web
 * server stops, so the last alert before a deploy is not the one that
 * disappears. Spring bounds the wait with its per-phase shutdown timeout,
 * which stands in for the original's "a second signal exits now".
 */
class TickguardLifecycle(
    private val app: Tickguard,
    private val config: Config,
    private val autoStartup: Boolean = true,
) : SmartLifecycle {
    @Volatile private var running = false
    private var scheduler: Scheduler? = null
    private var publisher: Scheduler? = null

    private val engine: CoroutineScope get() = app.engineScope()

    override fun start() {
        running = true
        // The status page and metrics read a snapshot the engine publishes; it
        // runs from the start, so a server waiting on its network shows it.
        publisher =
            Scheduler(
                listOf(ScheduledTask("snapshot", SNAPSHOT_EVERY, immediate = true) { app.tasks.publishSnapshot() }),
                engine,
            ).also { it.start() }
        engine.launch {
            app.start()
            // Not before: the SLA check and the daily calendar load both assume
            // the first calendar load has happened.
            if (running) scheduler = periodicWork().also { it.start() }
        }
    }

    override fun stop(callback: Runnable) {
        running = false
        scheduler?.stop()
        publisher?.stop()
        engine.launch {
            log.info("draining")
            try {
                app.stop()
            } finally {
                log.info("stopped")
                callback.run()
            }
        }
    }

    override fun stop() = stop {}

    override fun isRunning() = running

    override fun isAutoStartup() = autoStartup

    private fun periodicWork() =
        Scheduler(
            tasks =
                listOf(
                    ScheduledTask("holdings", config.intervals.holdings) { app.tasks.refreshHoldings() },
                    ScheduledTask("sla", config.intervals.slaCheck) { app.tasks.checkSla() },
                    ScheduledTask("reconcile", config.intervals.reconcile) { app.tasks.reconcile() },
                    ScheduledTask("prune", config.intervals.prune) { app.tasks.prune() },
                    ScheduledTask("fallback", config.fallback.interval) { app.tasks.pollFallbackQuotes() },
                    ScheduledTask("news", config.intervals.news, immediate = true) { app.tasks.collectNews() },
                    dailyTask("calendar") { app.tasks.loadCalendars() },
                    ScheduledTask("bars", config.intervals.bars, immediate = true) { app.tasks.refreshBars() },
                    // After the morning's bar refresh, long before the evening's session.
                    timeOfDayTask("rebalance", REBALANCE_AT) { app.sleeves.propose() },
                    // Places the latest proposals once, when the calendar says the order window is open.
                    ScheduledTask("execute", EXECUTION_CHECK) { app.sleeves.execute() },
                ),
            scope = engine,
            onError = { error, task -> log.error("scheduled task {} failed: {}", task, reasonOf(error)) },
        )

    private companion object {
        val log: Logger = LoggerFactory.getLogger(TickguardLifecycle::class.java)

        /** When a rebalance day's proposal goes out, in Seoul: the US session opens at 22:30 or 23:30. */
        val REBALANCE_AT: LocalTime = LocalTime.of(9, 0)

        /** How often the order window is looked at. A minute late into a six-hour window is nothing. */
        val EXECUTION_CHECK = 1.minutes

        /** How stale the status page and metrics may be. */
        val SNAPSHOT_EVERY = 5.seconds
    }
}
