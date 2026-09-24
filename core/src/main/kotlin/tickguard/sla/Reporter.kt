package tickguard.sla

import tickguard.rules.Signal
import java.time.Instant
import java.time.InstantSource
import kotlin.math.roundToLong
import kotlin.time.Duration

data class ReporterStats(
    val reported: Int,
    val inhibited: Int,
    val recovered: Int,
    /** Silences that ended before they were sent, and so were never sent. */
    val withdrawn: Int = 0,
)

/**
 * Turns SLA incidents and recoveries into alerts, and pairs them.
 *
 * A feed that went silent is paged; when it comes back, that is paged too,
 * as Alertmanager does with send_resolved. Otherwise the last word on a
 * symbol is "no quotes for ten minutes", and whether it is still true has to
 * be looked up.
 *
 * A recovery is sent only for an incident that was sent. Incidents raised
 * while held back — every feed goes quiet while the IP is refused, and that
 * cause was reported once already — would otherwise end in a "recovered"
 * alert for something the reader never heard about.
 *
 * A silence that ends while its page is still waiting to go out is taken
 * back instead of paired: silence then recovery, back to back, tells the
 * reader nothing they need.
 *
 * A held-back incident is not forgotten. The watcher offers it again at every
 * check, and once the cause is resolved a feed that is still silent is paged
 * like any other: the IP being allowed again does not mean every feed came
 * back with it. A held outage is counted once, not once per check.
 */
class IncidentReporter(
    private val report: (Signal) -> Unit,
    /** True while incidents are held back behind a cause already reported. */
    private val inhibited: () -> Boolean,
    private val clock: InstantSource = InstantSource.system(),
    /** Takes back matching signals not yet sent. True when one was. */
    private val withdraw: ((Signal) -> Boolean) -> Boolean = { false },
) {
    private val open = LinkedHashSet<String>()

    /** When each held-back silence began: the same outage offered again is not counted again. */
    private val heldSince = LinkedHashMap<String, Instant>()
    private var reported = 0
    private var held = 0
    private var recovered = 0
    private var withdrawn = 0

    /** True when paged; false when held back. */
    fun incident(incident: Incident): Boolean {
        if (inhibited()) {
            if (heldSince.put(incident.code, incident.since) != incident.since) held += 1
            return false
        }
        heldSince -= incident.code
        open += incident.code
        reported += 1
        report(
            Signal(
                ruleId = SILENCE,
                code = incident.code,
                title = "${incident.code} 시세가 ${minutes(incident.silentFor)}분째 없음",
                detail = "${incident.market} 시장은 열려 있음",
                firedAt = clock.instant(),
            ),
        )
        return true
    }

    fun recovered(
        code: String,
        silentFor: Duration,
    ) {
        if (!open.remove(code)) return
        if (withdraw { it.ruleId == SILENCE && it.code == code }) {
            withdrawn += 1
            return
        }
        recovered += 1
        report(
            Signal(
                ruleId = "sla-recovered",
                code = code,
                title = "$code 시세 복구 · ${minutes(silentFor)}분간 없었음",
                detail = "시세가 다시 들어오고 있음",
                firedAt = clock.instant(),
            ),
        )
    }

    fun stats() = ReporterStats(reported = reported, inhibited = held, recovered = recovered, withdrawn = withdrawn)

    /** Rounded half up, and never zero: "0분" reads as no silence at all. */
    private fun minutes(silentFor: Duration): Long =
        maxOf(1, (silentFor.inWholeMilliseconds / MILLIS_PER_MINUTE).roundToLong())

    private companion object {
        /** The rule id a silence page carries, and what a recovery looks for to take back. */
        const val SILENCE = "sla-silence"

        /** Minutes in an alert, from a duration in milliseconds. */
        const val MILLIS_PER_MINUTE = 60_000.0
    }
}
