package tickguard.sla

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.InstantSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class WatcherTest {
    private var now = 0L
    private var open = true
    private val incidents = mutableListOf<Incident>()
    private val recoveries = mutableListOf<Pair<String, Duration>>()

    private fun watcher(isOpen: (Market, Instant) -> Boolean = { _, _ -> open }) =
        SlaWatcher(
            isOpen = isOpen,
            clock = InstantSource { Instant.ofEpochMilli(now) },
            onIncident = { incidents += it },
            onRecovered = { code, silent -> recoveries += code to silent },
        )

    private fun advance(by: Duration) {
        now += by.inWholeMilliseconds
    }

    @Test
    fun `reports silence past the threshold while the market is open`() {
        val watcher = watcher()
        watcher.observed("005930", Market.KR)

        advance(9.minutes)
        watcher.check()
        assertThat(incidents).isEmpty()

        advance(2.minutes)
        watcher.check()
        assertThat(incidents.single().silentFor).isEqualTo(11.minutes)
    }

    @Test
    fun `stays quiet while the market is closed`() {
        // No ticks overnight is not an outage, and an alert every evening is one nobody reads.
        open = false
        val watcher = watcher()
        watcher.observed("005930", Market.KR)

        advance(12.hours)
        watcher.check()

        assertThat(incidents).isEmpty()
    }

    @Test
    fun `does not count a closed session as silence once it reopens`() {
        open = false
        val watcher = watcher()
        watcher.observed("005930", Market.KR)

        advance(12.hours)
        watcher.check()
        open = true
        watcher.check()

        assertThat(incidents).isEmpty()
    }

    @Test
    fun `reports one incident per outage, not one per check`() {
        val watcher = watcher()
        watcher.observed("005930", Market.KR)

        advance(30.minutes)
        watcher.check()
        watcher.check()
        advance(30.minutes)
        watcher.check()

        assertThat(incidents).hasSize(1)
    }

    @Test
    fun `reports recovery when ticks return`() {
        val watcher = watcher()
        watcher.observed("005930", Market.KR)
        advance(20.minutes)
        watcher.check()

        watcher.observed("005930", Market.KR)

        assertThat(recoveries).containsExactly("005930" to 20.minutes)
    }

    @Test
    fun `reports again after recovering and going quiet a second time`() {
        val watcher = watcher()
        watcher.observed("005930", Market.KR)
        advance(20.minutes)
        watcher.check()
        watcher.observed("005930", Market.KR)

        advance(20.minutes)
        watcher.check()

        assertThat(incidents).hasSize(2)
    }

    @Test
    fun `watches a symbol that has never ticked, so a feed that never starts counts`() {
        val watcher = watcher()
        watcher.expect("NVDA", Market.US)

        advance(20.minutes)
        watcher.check()

        assertThat(incidents.single().code).isEqualTo("NVDA")
    }

    @Test
    fun `stops watching a symbol that was sold`() {
        val watcher = watcher()
        watcher.expect("NVDA", Market.US)
        watcher.forget("NVDA")

        advance(20.minutes)
        watcher.check()

        assertThat(incidents).isEmpty()
        assertThat(watcher.stats().watched).isZero()
    }

    @Test
    fun `never reports when hours are unknown, rather than guessing`() {
        // A real calendar says closed until it has loaded.
        val watcher = watcher { _, _ -> false }
        watcher.expect("005930", Market.KR)

        advance(60.minutes)
        watcher.check()

        assertThat(incidents).isEmpty()
    }
}
