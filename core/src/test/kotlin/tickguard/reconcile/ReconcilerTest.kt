package tickguard.reconcile

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import tickguard.testing.StubRest
import tickguard.testing.decimal
import java.time.Instant
import java.time.InstantSource
import kotlin.time.Duration.Companion.seconds

class ReconcilerTest {
    /** Copied from a live /api/v1/prices response. */
    private val live =
        """
        {"result":[
          {"symbol":"AAPL","timestamp":"2026-09-23T00:27:33.000+09:00","lastPrice":"343.36","currency":"USD"},
          {"symbol":"TSLA","timestamp":"2026-09-23T00:27:33.000+09:00","lastPrice":"376.815","currency":"USD"}]}
        """.trimIndent()

    private fun streamed(
        price: String,
        atMs: Long = 0,
    ) = StreamedPrice(decimal(price), Instant.ofEpochMilli(atMs))

    @Test
    fun `stays silent while the two views agree within tolerance`() =
        runTest {
            val drifts = mutableListOf<Drift>()
            val reconciler =
                Reconciler(StubRest(live), { mapOf("AAPL" to streamed("343.36")) }, onDrift = {
                    drifts +=
                        it
                })

            val report = reconciler.run()

            assertThat(report.checked).isEqualTo(1)
            assertThat(report.drifts).isEmpty()
            assertThat(drifts).isEmpty()
        }

    @Test
    fun `reports drift past the tolerance, with its direction`() =
        runTest {
            // Streamed 340 against a fetched 343.36 is about +0.99%.
            val reconciler =
                Reconciler(
                    StubRest(live),
                    { mapOf("AAPL" to streamed("340", 1_000)) },
                    clock = InstantSource { Instant.ofEpochMilli(6_000) },
                )

            val drift = reconciler.run().drifts.single()

            assertThat(drift.ratio.format(4)).isEqualTo("0.0099")
            assertThat(drift.streamAge).isEqualTo(5.seconds)
        }

    @Test
    fun `reports the worst drift as a number for metrics`() =
        runTest {
            val reconciler =
                Reconciler(StubRest(live), { mapOf("AAPL" to streamed("343.36"), "TSLA" to streamed("300")) })

            assertThat(reconciler.run().worst).isCloseTo(0.2561, within(0.0005))
        }

    @Test
    fun `skips a symbol that has not streamed a price yet`() =
        runTest {
            // Before the first tick there is nothing to disagree with, and calling
            // that drift would make every startup look like an incident.
            val report = Reconciler(StubRest(live), { mapOf("NVDA" to streamed("100")) }).run()

            assertThat(report.checked).isZero()
            assertThat(report.skipped).isEqualTo(1)
        }

    @Test
    fun `batches symbols rather than spending the group one call at a time`() =
        runTest {
            val rest = StubRest("""{"result":[]}""")
            val seen = List(120) { "S$it" to streamed("1") }.toMap()

            Reconciler(rest, { seen }).run()

            assertThat(rest.asked).hasSize(3)
            assertThat(
                rest.asked
                    .first()
                    .query
                    .getValue("symbols")
                    .orEmpty()
                    .split(","),
            ).hasSize(50)
        }

    @Test
    fun `does not call at all when nothing has streamed`() =
        runTest {
            val rest = StubRest(live)

            Reconciler(rest, { emptyMap() }).run()

            assertThat(rest.asked).isEmpty()
        }

    @Test
    fun `counts a failed run without throwing into the caller`() =
        runTest {
            val errors = mutableListOf<Exception>()
            val reconciler =
                Reconciler(StubRest(IllegalStateException("503")), { mapOf("AAPL" to streamed("343.36")) }, onError = {
                    errors +=
                        it
                })

            val report = reconciler.run()

            assertThat(report.checked).isZero()
            assertThat(reconciler.stats()).isEqualTo(ReconcilerStats(runs = 0, failures = 1, drifts = 0))
            assertThat(errors).hasSize(1)
        }
}
