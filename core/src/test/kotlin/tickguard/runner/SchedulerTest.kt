package tickguard.runner

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.InstantSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class SchedulerTest {
    private fun TestScope.advance(by: Duration) {
        advanceTimeBy(by)
        runCurrent()
    }

    @Test
    fun `keeps one failing task from stopping another`() =
        runTest {
            var healthy = 0
            val errors = mutableListOf<String>()
            val scheduler =
                Scheduler(
                    listOf(
                        ScheduledTask("broken", 1.seconds) { error("503") },
                        ScheduledTask("healthy", 1.seconds) {
                            healthy +=
                                1
                        },
                    ),
                    backgroundScope,
                    onError = { _, task -> errors += task },
                )

            scheduler.start()
            advance(1.seconds)

            assertThat(healthy).isEqualTo(1)
            assertThat(errors).containsExactly("broken")
            assertThat(scheduler.stats().getValue("broken").failures).isEqualTo(1)
        }

    @Test
    fun `does not stack a slow task behind itself`() =
        runTest {
            // Holdings polling against a waiting rate limiter is exactly this case.
            var started = 0
            val release = CompletableDeferred<Unit>()
            val scheduler =
                Scheduler(
                    listOf(
                        ScheduledTask("slow", 1.seconds) {
                            started += 1
                            release.await()
                        },
                    ),
                    backgroundScope,
                )

            scheduler.start()
            advance(3.seconds)

            assertThat(started).isEqualTo(1)
            release.complete(Unit)
        }

    @Test
    fun `runs an immediate task before the first interval`() =
        runTest {
            var runs = 0
            Scheduler(listOf(ScheduledTask("now", 1.minutes, immediate = true) { runs += 1 }), backgroundScope).start()
            runCurrent()

            assertThat(runs).isEqualTo(1)
        }

    @Test
    fun `stops firing after stop`() =
        runTest {
            var runs = 0
            val scheduler = Scheduler(listOf(ScheduledTask("t", 1.seconds) { runs += 1 }), backgroundScope)

            scheduler.start()
            scheduler.stop()
            advance(3.seconds)

            assertThat(runs).isZero()
        }

    @Test
    fun `a daily task fires on the KST date change, not on a fixed interval`() =
        runTest {
            var now = Instant.parse("2026-09-23T10:00:00+09:00")
            var runs = 0
            Scheduler(listOf(dailyTask("calendar", { now }) { runs += 1 }), backgroundScope).start()
            runCurrent()
            // The immediate run saw the same date, so nothing yet.
            assertThat(runs).isZero()

            now = Instant.parse("2026-09-23T23:59:00+09:00")
            advance(1.minutes)
            assertThat(runs).isZero()

            now = Instant.parse("2026-09-24T00:01:00+09:00")
            advance(1.minutes)

            // A US session runs through this boundary, which is when stale hours
            // would report a live market as closed.
            assertThat(runs).isEqualTo(1)
        }

    @Test
    fun `a daily task retries on the next tick when the midnight load fails`() =
        runTest {
            // Marking the date done before the load ran left yesterday's hours in
            // place for a whole day after one failed request at midnight.
            var now = Instant.parse("2026-09-23T23:59:00+09:00")
            var runs = 0
            val clock = InstantSource { now }
            Scheduler(
                listOf(
                    dailyTask("calendar", clock) {
                        runs += 1
                        check(runs > 1) { "ENOTFOUND" }
                    },
                ),
                backgroundScope,
            ).start()
            runCurrent()

            now = Instant.parse("2026-09-24T00:00:30+09:00")
            advance(1.minutes)
            assertThat(runs).isEqualTo(1)

            now = Instant.parse("2026-09-24T00:01:30+09:00")
            advance(1.minutes)
            assertThat(runs).isEqualTo(2)

            now = Instant.parse("2026-09-24T00:02:30+09:00")
            advance(1.minutes)
            assertThat(runs).isEqualTo(2)
        }
}
