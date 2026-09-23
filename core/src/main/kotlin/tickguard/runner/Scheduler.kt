package tickguard.runner

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tickguard.time.kstDate
import java.time.InstantSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class ScheduledTask(
    val name: String,
    val every: Duration,
    /** Run once at start, before the first interval elapses. */
    val immediate: Boolean = false,
    val run: suspend () -> Unit,
)

data class TaskStats(
    val runs: Int,
    val failures: Int,
    val running: Boolean,
)

/**
 * Runs the periodic work nobody else owns.
 *
 * Every module deliberately exposes `refresh()` / `check()` / `run()` and no
 * timer, because a module holding its own interval owns a lifecycle it cannot
 * stop cleanly. This is where that lifecycle lives, once.
 *
 * Tasks are isolated from each other. A reconcile that 503s must not stop the
 * SLA check, and a task that throws must not kill the process — a supervisor
 * that dies on a transient error is worse than no supervisor.
 *
 * Runs on the engine, so a task and the state it touches share one thread. A
 * task that waits on the network suspends and gives the engine back meanwhile.
 */
class Scheduler(
    private val tasks: List<ScheduledTask>,
    private val scope: CoroutineScope,
    private val onError: (Exception, String) -> Unit = { _, _ -> },
) {
    private val state = tasks.associate { it.name to MutableTaskState() }.toMutableMap()
    private val tickers = mutableListOf<Job>()

    private class MutableTaskState(
        var runs: Int = 0,
        var failures: Int = 0,
        var running: Boolean = false,
    )

    fun start() {
        for (task in tasks) {
            if (task.immediate) invoke(task)
            tickers +=
                scope.launch {
                    while (true) {
                        delay(task.every)
                        invoke(task)
                    }
                }
        }
    }

    fun stop() {
        tickers.forEach { it.cancel() }
        tickers.clear()
    }

    fun stats(): Map<String, TaskStats> = state.mapValues { (_, it) -> TaskStats(it.runs, it.failures, it.running) }

    @Suppress("TooGenericExceptionCaught") // One task's failure of any kind must not stop the others.
    private fun invoke(task: ScheduledTask) {
        val entry = state.getValue(task.name)
        // A slow task must not stack up behind itself. Holdings polling every five
        // minutes against a rate limiter that may be waiting is exactly the case.
        if (entry.running) return
        entry.running = true

        scope.launch {
            try {
                task.run()
                entry.runs += 1
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                entry.failures += 1
                onError(failure, task.name)
            } finally {
                entry.running = false
            }
        }
    }
}

/**
 * Fires when the KST calendar date changes rather than every N hours.
 *
 * Market hours are published per business day, so the moment that matters is
 * midnight in Seoul, not a fixed interval from process start. A US session
 * running 22:30 to 05:00 crosses that boundary mid-session, which is exactly
 * when stale hours would report a live market as closed.
 */
fun dailyTask(
    name: String,
    clock: InstantSource = InstantSource.system(),
    run: suspend () -> Unit,
): ScheduledTask {
    var lastDate = kstDate(clock.instant())
    return ScheduledTask(name, DAILY_CHECK, immediate = true) {
        val today = kstDate(clock.instant())
        if (today != lastDate) {
            // Marked done only once it has worked. A load that fails at midnight is
            // then retried on the next minute's tick; marking it first would leave
            // yesterday's hours in place until the following midnight, and stale
            // hours read as a closed market, which silences every SLA alert.
            run()
            lastDate = today
        }
    }
}

/** How often a daily task looks at the date. A minute late at midnight is fine. */
private val DAILY_CHECK = 1.minutes
