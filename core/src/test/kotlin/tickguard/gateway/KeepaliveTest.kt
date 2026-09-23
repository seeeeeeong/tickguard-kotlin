package tickguard.gateway

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tickguard.testing.VirtualClock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class KeepaliveTest {
    private val deadline = PING_INTERVAL * MAX_MISSED_PONGS
    private val check = PING_INTERVAL / CHECKS_PER_PING

    private class Harness(
        private val scope: TestScope,
        interval: Duration = PING_INTERVAL,
        answer: Boolean = false,
    ) {
        val clock = VirtualClock(scope.testScheduler)
        val sent = mutableListOf<String>()
        val reports = mutableListOf<Duration>()
        private var answering: Keepalive? = null

        val keepalive =
            startKeepalive(
                scope = scope.backgroundScope,
                clock = clock,
                send = {
                    sent += it
                    answering?.pong()
                },
                onUnresponsive = { reports += it },
                interval = interval,
            ).also { if (answer) answering = it }

        /** Time passing normally: the check fires on schedule. */
        fun advance(by: Duration) {
            scope.advanceTimeBy(by)
            scope.runCurrent()
        }

        /**
         * Time passing with the checks missing — a GC pause, a suspended host.
         * The check fires once, late, and nothing replays what it skipped.
         */
        fun stall(
            by: Duration,
            check: Duration,
        ) {
            clock.skew += (by - check).inWholeMilliseconds
            advance(check)
        }
    }

    @Test
    fun `sends a bare PING, not JSON`() =
        runTest {
            val h = Harness(this)
            h.advance(PING_INTERVAL)

            assertThat(h.sent).containsExactly(PING_PAYLOAD)
        }

    @Test
    fun `checks more often than it pings`() =
        runTest {
            val h = Harness(this)
            h.advance(PING_INTERVAL - check)

            assertThat(h.sent).isEmpty()
        }

    @Test
    fun `keeps pinging while pongs come back`() =
        runTest {
            val h = Harness(this)
            repeat(10) {
                h.advance(PING_INTERVAL)
                h.keepalive.pong()
            }

            assertThat(h.sent).hasSize(10)
            assertThat(h.reports).isEmpty()
        }

    @Test
    fun `reports a socket that stops answering`() =
        runTest {
            // Writes succeed into a dead connection, so silence is the only signal.
            val h = Harness(this)

            h.advance(deadline)
            assertThat(h.reports).isEmpty()

            h.advance(check)
            assertThat(h.reports).containsExactly(deadline + check)
        }

    @Test
    fun `notices the deadline within a check of it, well inside the server's 180s`() =
        runTest {
            val h = Harness(this)
            h.advance(deadline + check)

            assertThat(h.reports.single()).isLessThanOrEqualTo(deadline + check).isLessThan(180.seconds)
        }

    @Test
    fun `catches silence that a stalled host hid`() =
        runTest {
            // The reason liveness is wall-clock and not a count of missed pings:
            // the runtime collapses every skipped check into one late wake-up, so
            // a counter would register this half hour as a single miss.
            val h = Harness(this)
            h.stall(30.minutes, check)

            assertThat(h.reports).containsExactly(30.minutes)
        }

    @Test
    fun `forgives a late pong and resumes`() =
        runTest {
            val h = Harness(this)

            h.advance(deadline)
            h.keepalive.pong()
            h.advance(PING_INTERVAL)

            assertThat(h.reports).isEmpty()
            assertThat(h.sent).hasSize(3)
        }

    @Test
    fun `reports once rather than on every later check`() =
        runTest {
            val h = Harness(this)
            h.advance(deadline + check * 5)

            assertThat(h.reports).hasSize(1)
        }

    @Test
    fun `stops pinging once unresponsive, rather than writing into a dead socket`() =
        runTest {
            val h = Harness(this)
            h.advance(deadline + PING_INTERVAL * 10)

            assertThat(h.sent).hasSize(MAX_MISSED_PONGS)
        }

    @Test
    fun `keeps a healthy connection alive at a short ping interval`() =
        runTest {
            // The original's probe pings every 3s. With the check period fixed at
            // 15s, the first check found 15s of silence against a 6s deadline and
            // killed a connection that had never been pinged. Found live.
            val h = Harness(this, interval = 3.seconds, answer = true)
            h.advance(30.seconds)

            assertThat(h.reports).isEmpty()
            assertThat(h.sent).hasSize(10)
        }

    @Test
    fun `sends nothing after stop`() =
        runTest {
            val h = Harness(this)
            h.keepalive.stop()
            h.advance(PING_INTERVAL)

            assertThat(h.sent).isEmpty()
        }

    @Test
    fun `reports how long it has been silent`() =
        runTest {
            val h = Harness(this)
            h.advance(PING_INTERVAL)

            assertThat(h.keepalive.silentFor()).isEqualTo(PING_INTERVAL)

            h.keepalive.pong()
            assertThat(h.keepalive.silentFor()).isEqualTo(Duration.ZERO)
        }
}
