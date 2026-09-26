package tickguard.execution

import tickguard.trading.SleeveMode
import java.time.Instant

/**
 * What a person switched on the control page, on top of configuration.
 *
 * It only ever moves one step. [liveUntil] runs a sleeve configured `DRY_RUN`
 * as `LIVE` until the order window it was switched in closes, and is held in
 * memory only. [daily] runs the named `DRY_RUN` sleeves as `LIVE` every day
 * until switched off; it is kept on the data volume by the caller and shown
 * on the page and in Discord, so it cannot move money unseen. An `OFF` sleeve
 * stays off, and nothing here can turn on a kill switch configuration left
 * off. [stopped] turns every order off.
 */
data class Arming(
    val stopped: Boolean = false,
    val liveUntil: Instant? = null,
    val daily: Set<String> = emptySet(),
) {
    fun enabled(configured: Boolean): Boolean = configured && !stopped

    fun modes(
        configured: Map<String, SleeveMode>,
        now: Instant,
    ): Map<String, SleeveMode> =
        configured.mapValues { (id, mode) ->
            val live = isLive(now) || (!stopped && id in daily)
            if (mode == SleeveMode.DRY_RUN && live) SleeveMode.LIVE else mode
        }

    fun isLive(now: Instant): Boolean = !stopped && liveUntil?.let { now.isBefore(it) } == true
}
