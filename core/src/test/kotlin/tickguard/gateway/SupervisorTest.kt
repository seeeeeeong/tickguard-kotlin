package tickguard.gateway

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tickguard.auth.TokenIssueError
import tickguard.testing.VirtualClock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class SupervisorTest {
    /** Stands in for connect(): a test decides per attempt whether it opens or fails. */
    private class Harness(
        private val scope: TestScope,
        getToken: (suspend () -> String)? = null,
        healthyAfter: Duration = HEALTHY_AFTER,
    ) {
        val clock = VirtualClock(scope.testScheduler)
        val opened = mutableListOf<ConnectionHandlers>()
        val attempts = mutableListOf<Long>()
        val onOpen = mutableListOf<Connection>()
        var onClosed = 0
        val frames = mutableListOf<ServerFrame>()
        val gaveUp = mutableListOf<Throwable>()
        val blocked = mutableListOf<Throwable>()
        val unblocked = mutableListOf<Duration>()
        val invalidated = mutableListOf<String>()
        var failNext: Throwable? = null
        var failWhile: () -> Throwable? = { null }

        val supervisor =
            startSupervisor(
                scope.backgroundScope,
                SupervisorOptions(
                    getToken = getToken ?: { "token" },
                    invalidateToken = { invalidated += it },
                    connect = { _, handlers ->
                        attempts += clock.millis()
                        failNext?.let {
                            failNext = null
                            throw it
                        }
                        failWhile()?.let { throw it }
                        opened += handlers
                        object : Connection {
                            override fun send(payload: String) = Unit

                            override fun close() = handlers.onClosed(ConnectionClosed(1006))
                        }
                    },
                    onOpen = { onOpen += it },
                    onClosed = { onClosed += 1 },
                    onFrame = { frames += it },
                    onGaveUp = { gaveUp += it },
                    onBlocked = { blocked += it },
                    onUnblocked = { unblocked += it },
                    clock = clock,
                    random = { 0.0 },
                    healthyAfter = healthyAfter,
                ),
            )

        init {
            scope.runCurrent()
        }

        /**
         * Closes connection [index] from the server side and returns how long
         * until the next attempt. Time is advanced by hand: the test scheduler
         * counts a supervisor running in the background scope as idle.
         */
        fun closeAndMeasure(index: Int): Duration {
            val closedAt = clock.millis()
            opened[index].onClosed(ConnectionClosed(1006))
            advance(10.seconds)
            return (attempts.last() - closedAt).milliseconds
        }

        fun advance(by: Duration) {
            scope.advanceTimeBy(by)
            scope.runCurrent()
        }
    }

    @Test
    fun `reconnects after the connection closes`() =
        runTest {
            val h = Harness(this)
            assertThat(h.opened).hasSize(1)

            h.closeAndMeasure(0)

            assertThat(h.opened).hasSize(2)
            h.supervisor.stop()
        }

    @Test
    fun `fires onOpen on every connection, since a new socket has no subscriptions`() =
        runTest {
            val h = Harness(this)
            h.closeAndMeasure(0)

            assertThat(h.onOpen).hasSize(2)
            h.supervisor.stop()
        }

    @Test
    fun `reports a close before waiting to reconnect, so nothing reads a dead socket as up`() =
        runTest {
            val h = Harness(this)
            assertThat(h.onClosed).isZero()

            h.opened[0].onClosed(ConnectionClosed(1006))
            runCurrent()

            assertThat(h.onClosed).isEqualTo(1)
            assertThat(h.opened).hasSize(1)
            h.supervisor.stop()
        }

    @Test
    fun `keeps retrying a 403 slowly, since registering the IP fixes it without a restart`() =
        runTest {
            val h = Harness(this)
            h.failWhile = { HandshakeError.fromStatus(403) }

            h.opened[0].onClosed(ConnectionClosed(1006))
            h.advance(500.milliseconds)
            val blockedAt = h.attempts.last()
            h.advance(BLOCKED_RETRY)

            assertThat(h.gaveUp).isEmpty()
            assertThat(h.blocked).hasSize(1)
            assertThat(h.attempts.last() - blockedAt).isEqualTo(BLOCKED_RETRY.inWholeMilliseconds)
            h.supervisor.stop()
        }

    @Test
    fun `reports a block once, not on every retry`() =
        runTest {
            val h = Harness(this)
            h.failWhile = { HandshakeError.fromStatus(403) }

            h.opened[0].onClosed(ConnectionClosed(1006))
            h.advance(BLOCKED_RETRY * 3 + 1.seconds)

            assertThat(h.attempts).hasSizeGreaterThan(3)
            assertThat(h.blocked).hasSize(1)
            h.supervisor.stop()
        }

    @Test
    fun `reports how long the block lasted once a connection opens again`() =
        runTest {
            val h = Harness(this)
            val registeredAt = 500 + 23.minutes.inWholeMilliseconds
            h.failWhile = { if (h.clock.millis() < registeredAt) HandshakeError.fromStatus(403) else null }

            h.opened[0].onClosed(ConnectionClosed(1006))
            h.advance(24.minutes)

            assertThat(h.opened).hasSize(2)
            assertThat(h.unblocked).containsExactly(23.minutes)
            h.supervisor.stop()
        }

    @Test
    fun `treats a 403 from token issuance as the same block`() =
        runTest {
            // The docs do not say whether the token endpoint enforces the allow
            // list, so a 403 there must not fall through to an unexplained retry loop.
            var refuse = false
            val refused = TokenIssueError.fromResponse(403, "")
            val h = Harness(this, getToken = { if (refuse) throw refused else "token" })

            refuse = true
            h.opened[0].onClosed(ConnectionClosed(1006))
            h.advance(1.seconds)

            assertThat(h.blocked).containsExactly(refused)
            h.supervisor.stop()
        }

    @Test
    fun `retries a 401 once with a fresh token before giving up`() =
        runTest {
            val h = Harness(this)
            h.failNext = HandshakeError.fromStatus(401)

            h.closeAndMeasure(0)

            assertThat(h.invalidated).containsExactly("token")
            assertThat(h.gaveUp).isEmpty()
            assertThat(h.opened).hasSize(2)
            h.supervisor.stop()
        }

    @Test
    fun `gives up on a second 401 in a row, since the credentials themselves are wrong`() =
        runTest {
            val h = Harness(this)
            h.failWhile = { HandshakeError.fromStatus(401) }

            h.opened[0].onClosed(ConnectionClosed(1006))
            h.advance(10.seconds)

            assertThat(h.gaveUp).hasSize(1)
            assertThat(h.attempts).hasSize(3)
        }

    @Test
    fun `keeps retrying a 503, which is transient`() =
        runTest {
            val h = Harness(this)
            h.failNext = HandshakeError.fromStatus(503)

            h.closeAndMeasure(0)

            assertThat(h.gaveUp).isEmpty()
            assertThat(h.opened).hasSize(2)
            h.supervisor.stop()
        }

    @Test
    fun `does not reconnect after stop`() =
        runTest {
            val h = Harness(this)

            h.supervisor.stop()
            h.advance(10.seconds)

            assertThat(h.attempts).hasSize(1)
        }

    @Test
    fun `backs off further after each connection that dies immediately`() =
        runTest {
            // random() is 0, so each delay is exactly half its ceiling.
            val h = Harness(this)

            val delays = (0..2).map { h.closeAndMeasure(it) }

            assertThat(delays).containsExactly(500.milliseconds, 1.seconds, 2.seconds)
            h.supervisor.stop()
        }

    @Test
    fun `resets backoff after a connection that lasted`() =
        runTest {
            val h = Harness(this)

            // Dies immediately: the next wait must grow.
            val first = h.closeAndMeasure(0)
            // Stayed up past the healthy threshold: the next wait must start over.
            h.advance(60.seconds)
            val second = h.closeAndMeasure(1)

            assertThat(listOf(first, second)).containsExactly(500.milliseconds, 500.milliseconds)
            h.supervisor.stop()
        }

    @Test
    fun `drops frames a previous connection reads after it closed`() =
        runTest {
            val h = Harness(this)
            h.closeAndMeasure(0)

            h.opened[0].onFrame(ServerFrame.Pong)
            h.opened[1].onFrame(ServerFrame.Unknown("current"))

            assertThat(h.frames).containsExactly(ServerFrame.Unknown("current"))
            h.supervisor.stop()
        }

    @Test
    fun `retries anything that is not a handshake rejection`() {
        assertThat(classifyFailure(IllegalStateException("socket hung up"), false)).isEqualTo(FailureAction.RETRY)
        assertThat(classifyFailure(HandshakeTimeoutError(HANDSHAKE_TIMEOUT), false)).isEqualTo(FailureAction.RETRY)
    }

    @Test
    fun `retries a 503 by classification too`() {
        assertThat(classifyFailure(HandshakeError.fromStatus(503), false)).isEqualTo(FailureAction.RETRY)
    }

    @Test
    fun `tries a fresh token once on 401, then gives up`() {
        assertThat(
            classifyFailure(HandshakeError.fromStatus(401), false),
        ).isEqualTo(FailureAction.RETRY_WITH_FRESH_TOKEN)
        assertThat(classifyFailure(HandshakeError.fromStatus(401), true)).isEqualTo(FailureAction.GIVE_UP)
    }

    @Test
    fun `treats 403 as a block, fresh token or not`() {
        assertThat(classifyFailure(HandshakeError.fromStatus(403), false)).isEqualTo(FailureAction.BLOCKED)
        assertThat(classifyFailure(HandshakeError.fromStatus(403), true)).isEqualTo(FailureAction.BLOCKED)
    }
}
