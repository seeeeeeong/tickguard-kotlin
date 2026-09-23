package tickguard.runner

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class StartupTest {
    @Test
    fun `keeps trying through the minute a router takes to come up`() =
        runTest {
            var calls = 0
            val waits = mutableListOf<Duration>()
            val attempts = mutableListOf<Int>()

            val done =
                retryUntilDone(
                    run = {
                        calls += 1
                        check(calls > 2) { "openapi.tossinvest.com (UnknownHostException)" }
                    },
                    onFailure = { _, attempt, _ -> attempts += attempt },
                    sleep = { waits += it },
                    random = { 1.0 },
                )

            assertThat(done).isTrue()
            assertThat(calls).isEqualTo(3)
            assertThat(attempts).containsExactly(1, 2)
            // Backed off, not a tight loop against a network that is not there.
            assertThat(waits).containsExactly(1.seconds, 2.seconds)
        }

    @Test
    fun `stops waiting when cancelled, so a shutdown is not held up`() =
        runTest {
            var cancelled = false
            var calls = 0

            val done =
                retryUntilDone(
                    run = {
                        calls += 1
                        error("down")
                    },
                    onFailure = { _, _, _ -> },
                    isCancelled = { cancelled },
                    sleep = { cancelled = true },
                )

            assertThat(done).isFalse()
            assertThat(calls).isEqualTo(1)
        }
}
