package tickguard.notify

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tickguard.rules.Signal
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class SignalGroup(
    val key: String,
    val signals: List<Signal>,
)

/**
 * Holds a signal briefly so anything arriving alongside it goes out together.
 *
 * Alertmanager does the same thing and calls it `group_wait`, defaulting to
 * 30 seconds. The reason is the market: when it turns, symbols do not cross
 * their thresholds one at a time. Eight positions falling together produce
 * eight separate pushes seconds apart, and by the fourth the phone is noise
 * rather than information.
 *
 * The wait is per group, started by the first signal in it and not extended
 * by later ones. Extending on every arrival is a debounce, and a debounce
 * during a sustained move never fires at all.
 *
 * Confined to the engine: a group is opened, joined and released there, so its
 * timer and a signal joining it can never interleave.
 */
class Grouper(
    private val scope: CoroutineScope,
    private val onGroup: (SignalGroup) -> Unit,
    private val wait: Duration = DEFAULT_WAIT,
    /** Signals sharing this go out together. Rule id by default. */
    private val groupKey: (Signal) -> String = { it.ruleId },
) {
    private val waiting = LinkedHashMap<String, MutableList<Signal>>()
    private val timers = LinkedHashMap<String, Job>()

    fun add(signal: Signal) {
        val key = groupKey(signal)
        val existing = waiting[key]
        if (existing != null) {
            // Joining an open group: the timer is not restarted, or a sustained
            // move would keep pushing the send back and never arrive.
            existing += signal
            return
        }

        waiting[key] = mutableListOf(signal)
        if (wait == Duration.ZERO) {
            release(key)
            return
        }
        timers[key] =
            scope.launch {
                delay(wait)
                release(key)
            }
    }

    /** Sends everything waiting. For shutdown, so a pending group is not lost. */
    fun flush() {
        for (key in waiting.keys.toList()) {
            timers[key]?.cancel()
            release(key)
        }
    }

    fun pending(): Int = waiting.values.sumOf { it.size }

    private fun release(key: String) {
        timers -= key
        val signals = waiting.remove(key)
        if (!signals.isNullOrEmpty()) onGroup(SignalGroup(key, signals))
    }

    private companion object {
        /** Alertmanager's group_wait default, and long enough for a market turn. */
        val DEFAULT_WAIT = 30.seconds
    }
}
