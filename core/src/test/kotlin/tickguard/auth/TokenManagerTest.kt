package tickguard.auth

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tickguard.testing.failureOf
import tickguard.testing.supervisedScope
import java.time.Instant
import java.time.InstantSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class TokenManagerTest {
    private var now = 0L
    private val clock = InstantSource { Instant.ofEpochMilli(now) }

    /**
     * Counts calls and hands out a distinct token per call, so a test can tell
     * a reused token from a freshly issued one. Issuing always yields, which is
     * what lets concurrent callers actually overlap.
     */
    private class FakeIssuer(
        private val lifetime: Duration = 10.minutes,
        private val hold: Duration = Duration.ZERO,
        var fail: (() -> Exception)? = null,
    ) : TokenIssuer {
        var calls = 0

        override suspend fun issue(): IssuedToken {
            calls += 1
            val issuedOn = calls
            yield()
            if (hold > Duration.ZERO) delay(hold)
            fail?.let { throw it() }
            return IssuedToken("token-$issuedOn", Instant.ofEpochMilli(issuedOn * lifetime.inWholeMilliseconds))
        }
    }

    @Test
    fun `issues once when many callers arrive together`() =
        runTest {
            val issuer = FakeIssuer()
            val manager = TokenManager(issuer, clock, supervisedScope())

            val tokens = List(10) { async { manager.token() } }.awaitAll()

            assertThat(issuer.calls).isEqualTo(1)
            assertThat(tokens.toSet()).containsExactly("token-1")
        }

    @Test
    fun `reuses a cached token that is still comfortably valid`() =
        runTest {
            val issuer = FakeIssuer(lifetime = 10.minutes)
            val manager = TokenManager(issuer, clock, supervisedScope())

            manager.token()
            now += 5.minutes.inWholeMilliseconds
            manager.token()

            assertThat(issuer.calls).isEqualTo(1)
        }

    @Test
    fun `refreshes before expiry rather than waiting for a 401`() =
        runTest {
            val issuer = FakeIssuer(lifetime = 10.minutes)
            val manager = TokenManager(issuer, clock, supervisedScope())

            manager.token()
            // 30s short of expiry: still valid, but inside the refresh skew.
            now += (9.minutes + 30.seconds).inWholeMilliseconds

            assertThat(manager.token()).isEqualTo("token-2")
            assertThat(issuer.calls).isEqualTo(2)
        }

    @Test
    fun `does not cache a failure, so the next caller retries`() =
        runTest {
            val issuer = FakeIssuer(fail = { IllegalStateException("boom") })
            val manager = TokenManager(issuer, clock, supervisedScope())

            assertThat(failureOf { manager.token() }).hasMessage("boom")

            issuer.fail = null
            assertThat(manager.token()).isEqualTo("token-2")
            assertThat(issuer.calls).isEqualTo(2)
        }

    @Test
    fun `propagates the failure to every caller that shared the attempt`() =
        runTest {
            val issuer = FakeIssuer(fail = { IllegalStateException("boom") })
            val manager = TokenManager(issuer, clock, supervisedScope())

            val failures = List(2) { async { failureOf { manager.token() } } }.awaitAll()

            assertThat(failures).allMatch { it?.message == "boom" }
            assertThat(issuer.calls).isEqualTo(1)
        }

    @Test
    fun `a cancelled caller does not take the shared issue down with it`() =
        runTest {
            val issuer = FakeIssuer(hold = 1.seconds)
            val manager = TokenManager(issuer, clock, supervisedScope())

            val first = launch { manager.token() }
            val second = async { manager.token() }
            runCurrent()
            first.cancel()

            assertThat(second.await()).isEqualTo("token-1")
            assertThat(issuer.calls).isEqualTo(1)
        }

    @Test
    fun `invalidating the current token issues a fresh one on the next call`() =
        runTest {
            val issuer = FakeIssuer()
            val manager = TokenManager(issuer, clock, supervisedScope())

            val revoked = manager.token()
            manager.invalidate(revoked)

            assertThat(manager.token()).isEqualTo("token-2")
        }

    @Test
    fun `a late 401 for a replaced token does not discard its replacement`() =
        runTest {
            val issuer = FakeIssuer()
            val manager = TokenManager(issuer, clock, supervisedScope())

            val old = manager.token()
            manager.invalidate(old)
            val fresh = manager.token()
            // A request sent with the old token answers now.
            manager.invalidate(old)

            assertThat(manager.token()).isEqualTo(fresh)
            assertThat(issuer.calls).isEqualTo(2)
        }
}
