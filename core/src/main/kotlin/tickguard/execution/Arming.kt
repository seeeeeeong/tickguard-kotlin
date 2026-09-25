package tickguard.execution

import tickguard.trading.SleeveMode
import java.time.Instant

/**
 * What a person switched on the control page, on top of configuration, and
 * held only in memory: a restart forgets it and falls back to configuration,
 * so the page can never leave money moving behind a person's back.
 *
 * It only ever moves one step. [liveUntil] runs a sleeve configured `DRY_RUN`
 * as `LIVE` until the order window it was switched in closes; an `OFF` sleeve
 * stays off, and nothing here can turn on a kill switch configuration left
 * off. [stopped] turns every order off until the restart.
 */
data class Arming(
    val stopped: Boolean = false,
    val liveUntil: Instant? = null,
) {
    fun enabled(configured: Boolean): Boolean = configured && !stopped

    fun modes(
        configured: Map<String, SleeveMode>,
        now: Instant,
    ): Map<String, SleeveMode> =
        configured.mapValues { (_, mode) ->
            if (mode == SleeveMode.DRY_RUN && isLive(now)) SleeveMode.LIVE else mode
        }

    fun isLive(now: Instant): Boolean = !stopped && liveUntil?.let { now.isBefore(it) } == true
}
