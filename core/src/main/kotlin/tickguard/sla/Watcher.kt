package tickguard.sla

import java.time.Instant
import java.time.InstantSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

data class Incident(
    val code: String,
    val market: Market,
    val silentFor: Duration,
    val since: Instant,
)

data class SlaStats(
    val watched: Int,
    val open: Int,
)

/**
 * Notices when a subscription stops producing while its market is open.
 *
 * The failure this catches is the quiet one: the socket is connected, the ack
 * said subscribed, and nothing arrives. Nothing in the protocol reports that —
 * quotes are LOSSY with no sequence number, so silence and a stopped feed are
 * the same bytes.
 *
 * Every condition is checked against the calendar first. A watcher that cannot
 * tell a closed market from a broken feed reports an outage every evening, and
 * an alert that fires every night is one nobody reads.
 *
 * Confined to the engine: ticks are observed there and checks run there.
 */
class SlaWatcher(
    private val isOpen: (Market, Instant) -> Boolean,
    /** Silence beyond this, while open, is an incident. */
    private val threshold: Duration = DEFAULT_THRESHOLD,
    private val clock: InstantSource = InstantSource.system(),
    private val onIncident: (Incident) -> Unit,
    private val onRecovered: (code: String, silentFor: Duration) -> Unit = { _, _ -> },
) {
    private class Watched(
        var market: Market,
        var lastSeenAt: Instant,
        var incidentSince: Instant?,
    )

    private val watched = LinkedHashMap<String, Watched>()

    /** Called per decoded tick. Cheap: a map write. */
    fun observed(
        code: String,
        market: Market,
    ) {
        val at = clock.instant()
        val entry = watched.getOrPut(code) { Watched(market, at, null) }
        entry.market = market

        if (entry.incidentSince != null) {
            onRecovered(code, between(entry.lastSeenAt, at))
            entry.incidentSince = null
        }
        entry.lastSeenAt = at
    }

    /** Watch a symbol that has not ticked yet, so a feed that never starts counts. */
    fun expect(
        code: String,
        market: Market,
    ) {
        watched.putIfAbsent(code, Watched(market, clock.instant(), null))
    }

    fun forget(code: String) {
        watched -= code
    }

    /** Called on a timer by whoever owns the schedule. */
    fun check() {
        val at = clock.instant()
        for ((code, entry) in watched) inspect(code, entry, at)
    }

    fun stats(): SlaStats {
        val at = clock.instant()
        return SlaStats(watched = watched.size, open = watched.values.count { isOpen(it.market, at) })
    }

    private fun inspect(
        code: String,
        entry: Watched,
        at: Instant,
    ) {
        if (!isOpen(entry.market, at)) {
            // Closed: the clock restarts so a whole closed session is not counted
            // as silence the moment it opens again.
            entry.lastSeenAt = at
            return
        }

        val silentFor = between(entry.lastSeenAt, at)
        // One incident per outage, not one per check.
        if (silentFor < threshold || entry.incidentSince != null) return

        entry.incidentSince = at
        onIncident(Incident(code, entry.market, silentFor, entry.lastSeenAt))
    }

    private fun between(
        from: Instant,
        to: Instant,
    ): Duration = (to.toEpochMilli() - from.toEpochMilli()).milliseconds

    private companion object {
        /** Ten minutes of nothing on an open market: long past a quiet spell, short of a lost morning. */
        val DEFAULT_THRESHOLD = 10.minutes
    }
}
