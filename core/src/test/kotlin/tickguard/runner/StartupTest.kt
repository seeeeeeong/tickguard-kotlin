package tickguard.runner

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tickguard.auth.TokenIssueError
import tickguard.gateway.BLOCKED_RETRY
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
                    onFailure = { _, attempt, _, _ -> attempts += attempt },
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
    fun `waits for a person at the blocked cadence, not a backoff, when the IP is refused`() =
        runTest {
            var calls = 0
            val waits = mutableListOf<Duration>()

            retryUntilDone(
                run = {
                    calls += 1
                    if (calls <= 3) throw TokenIssueError.fromResponse(403, "")
                },
                onFailure = { _, _, _, _ -> },
                sleep = { waits += it },
                random = { 1.0 },
            )

            assertThat(waits).containsExactly(BLOCKED_RETRY, BLOCKED_RETRY, BLOCKED_RETRY)
        }

    @Test
    fun `says which failures repeat the one before, so a block is reported once`() =
        runTest {
            val failures = listOf("down", "down", "refused", "refused", "down")
            var calls = 0
            val repeated = mutableListOf<Boolean>()

            retryUntilDone(
                run = {
                    calls += 1
                    failures.getOrNull(calls - 1)?.let { error(it) }
                },
                onFailure = { _, _, _, same -> repeated += same },
                sleep = {},
            )

            assertThat(repeated).containsExactly(false, true, false, true, false)
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
                    onFailure = { _, _, _, _ -> },
                    isCancelled = { cancelled },
                    sleep = { cancelled = true },
                )

            assertThat(done).isFalse()
            assertThat(calls).isEqualTo(1)
        }
}
