package tickguard.reconcile

import kotlinx.coroutines.CancellationException
import tickguard.rest.RestClient
import tickguard.rest.fetchPrices
import tickguard.stream.Decimal
import tickguard.stream.changeRatio
import java.time.Instant
import java.time.InstantSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

data class StreamedPrice(
    val price: Decimal,
    val at: Instant,
)

data class Drift(
    val code: String,
    val streamed: Decimal,
    val fetched: Decimal,
    /** Signed ratio: fetched relative to streamed. 0.01 means REST is 1% higher. */
    val ratio: Decimal,
    val streamAge: Duration,
)

data class ReconcileReport(
    val checked: Int,
    val skipped: Int,
    val drifts: List<Drift>,
    /** Largest absolute drift ratio seen, as a plain number for metrics. */
    val worst: Double,
)

data class ReconcilerStats(
    val runs: Int,
    val failures: Int,
    val drifts: Int,
)

/**
 * Compares the last price seen on the socket against the same price fetched
 * over REST.
 *
 * This exists because quote frames are LOSSY and carry no sequence number, so
 * a dropped frame is undetectable from the stream alone. The only way to know
 * the socket view is still the market view is to ask a second time by another
 * road.
 *
 * A disagreement is not proof of loss — the two reads are seconds apart and a
 * price moves — so the output is a drift measurement, not a verdict. A drift
 * that stays small says the stream is healthy; one that grows and does not
 * come back says frames are being lost.
 *
 * The stream keeps moving while the REST call is in flight, and the answer
 * may reflect a trade from either side of it. So the REST price is compared
 * with the streamed price from before the call and from after it, and the
 * nearer one counts: agreeing with either is agreeing with the stream.
 *
 * Symbols with no streamed price are skipped rather than reported. Before the
 * first tick there is nothing to disagree with, and calling that drift would
 * make every startup look like an incident.
 */
class Reconciler(
    private val rest: RestClient,
    /** Last streamed price per symbol, owned by whoever consumes the stream. */
    private val lastSeen: () -> Map<String, StreamedPrice>,
    /** Reported above this absolute ratio. 0.005 is half a percent. */
    toleranceRatio: String = "0.005",
    private val clock: InstantSource = InstantSource.system(),
    private val onDrift: (Drift) -> Unit = {},
    private val onError: (Exception) -> Unit = {},
) {
    private val tolerance = Decimal.parse(toleranceRatio, "toleranceRatio")
    private var runs = 0
    private var failures = 0
    private var drifts = 0

    @Suppress("TooGenericExceptionCaught") // A failed run is counted; it must never reach the scheduler.
    suspend fun run(): ReconcileReport {
        val before = lastSeen().toMap()
        if (before.isEmpty()) return EMPTY

        return try {
            val fetched = fetchPrices(rest, before.keys.toList())
            runs += 1
            compare(before, lastSeen(), fetched).also { report ->
                drifts += report.drifts.size
                report.drifts.forEach(onDrift)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failures += 1
            onError(failure)
            EMPTY
        }
    }

    fun stats() = ReconcilerStats(runs, failures, drifts)

    private fun compare(
        before: Map<String, StreamedPrice>,
        after: Map<String, StreamedPrice>,
        fetched: Map<String, Decimal>,
    ): ReconcileReport {
        val found = mutableListOf<Drift>()
        var checked = 0
        var skipped = 0
        var worst = 0.0
        val at = clock.millis()

        for ((code, earlier) in before) {
            val reference = fetched[code]
            if (reference == null || earlier.price.isZero()) {
                skipped += 1
                continue
            }

            checked += 1
            val streamed = nearer(reference, earlier, after[code])
            val ratio = changeRatio(streamed.price, reference)
            worst = maxOf(worst, ratio.abs().toDouble())
            if (ratio.abs() >= tolerance) {
                found += Drift(code, streamed.price, reference, ratio, (at - streamed.at.toEpochMilli()).milliseconds)
            }
        }
        return ReconcileReport(checked, skipped, found, worst)
    }

    /** Whichever streamed price the REST answer is closer to. */
    private fun nearer(
        reference: Decimal,
        earlier: StreamedPrice,
        later: StreamedPrice?,
    ): StreamedPrice {
        if (later == null || later.price.isZero()) return earlier
        val fromEarlier = changeRatio(earlier.price, reference).abs()
        val fromLater = changeRatio(later.price, reference).abs()
        return if (fromLater < fromEarlier) later else earlier
    }

    private companion object {
        /** What a run reports when there was nothing to compare, or the comparison failed. */
        val EMPTY = ReconcileReport(checked = 0, skipped = 0, drifts = emptyList(), worst = 0.0)
    }
}
