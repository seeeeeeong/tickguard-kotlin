package tickguard.gateway

import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tickguard.testing.VirtualClock
import tickguard.testing.failureOf
import tickguard.testing.supervisedScope
import kotlin.time.Duration

class ConnectionTest {
    private val check = PING_INTERVAL / CHECKS_PER_PING

    /** Records what was sent and lets a test fire socket events by hand. */
    private class FakeSocket : Socket {
        val sent = mutableListOf<String>()
        var closed = false
        var terminated = false
        var accepts = true

        override fun send(payload: String): Boolean {
            if (accepts) sent += payload
            return accepts
        }

        override fun close() {
            closed = true
        }

        override fun terminate() {
            terminated = true
        }
    }

    private class Setup(
        private val scope: TestScope,
    ) {
        val socket = FakeSocket()
        lateinit var events: SocketEvents
        val frames = mutableListOf<ServerFrame>()
        val closes = mutableListOf<ConnectionClosed>()

        val connecting =
            scope.supervisedScope().async {
                connect(
                    ConnectOptions(
                        accessToken = "token",
                        handlers =
                            object : ConnectionHandlers {
                                override fun onFrame(frame: ServerFrame) {
                                    frames += frame
                                }

                                override fun onClosed(reason: ConnectionClosed) {
                                    closes += reason
                                }
                            },
                        sockets = { _, _, _, events -> socket.also { this@Setup.events = events } },
                        clock = VirtualClock(scope.testScheduler),
                        keepaliveScope = scope.backgroundScope,
                    ),
                )
            }

        init {
            scope.runCurrent()
        }

        suspend fun open(): Connection {
            events.onOpen()
            return connecting.await()
        }

        fun advance(by: Duration) {
            scope.advanceTimeBy(by)
            scope.runCurrent()
        }
    }

    @Test
    fun `returns only once the socket is open, so declarations are not sent early`() =
        runTest {
            val s = Setup(this)
            assertThat(s.connecting.isCompleted).isFalse()

            s.events.onOpen()
            runCurrent()

            assertThat(s.connecting.isCompleted).isTrue()
        }

    @Test
    fun `sends a bare PING, not JSON, on every interval`() =
        runTest {
            val s = Setup(this)
            s.open()

            s.advance(PING_INTERVAL * 2)

            assertThat(s.socket.sent).containsExactly(PING_PAYLOAD, PING_PAYLOAD)
        }

    @Test
    fun `gives up on a socket that stops answering, so a dead one is noticed`() =
        runTest {
            // A live run lost its network for an hour while writes kept succeeding.
            // Nothing in the transport reported it; only the unanswered pings can.
            val s = Setup(this)
            s.open()

            s.advance(PING_INTERVAL * MAX_MISSED_PONGS + check)

            assertThat(s.closes).hasSize(1)
            assertThat(s.closes.single().error).hasMessageContaining("silent for")
            // Terminated, not closed: a close frame into a dead link holds the
            // socket for the close timeout.
            assertThat(s.socket.terminated).isTrue()
            assertThat(s.socket.closed).isFalse()
        }

    @Test
    fun `keeps going while pongs come back`() =
        runTest {
            val s = Setup(this)
            s.open()

            repeat(10) {
                s.advance(PING_INTERVAL)
                s.events.onMessage("""{"type":"pong"}""")
            }

            assertThat(s.closes).isEmpty()
            assertThat(s.socket.sent).hasSize(10)
        }

    @Test
    fun `stops pinging once the connection is gone`() =
        runTest {
            val s = Setup(this)
            s.open()

            s.events.onClosed(1006)
            s.advance(PING_INTERVAL * 3)

            assertThat(s.socket.sent).isEmpty()
        }

    @Test
    fun `hands parsed frames to the caller`() =
        runTest {
            val s = Setup(this)
            s.open()

            s.events.onMessage("""{"type":"pong"}""")

            assertThat(s.frames).containsExactly(ServerFrame.Pong)
        }

    @Test
    fun `reports a close once, after opening, however many ways it is reported`() =
        runTest {
            val s = Setup(this)
            s.open()

            s.events.onClosed(1000)
            s.events.onFailure(IllegalStateException("after close"))
            s.events.onClosed(1006)

            assertThat(s.closes).containsExactly(ConnectionClosed(1000))
        }

    @Test
    fun `fails with an actionable error when the handshake is refused`() =
        runTest {
            val s = Setup(this)

            s.events.onHandshakeFailed(403)

            assertThat(failureOf { s.connecting.await() })
                .isInstanceOf(HandshakeError::class.java)
                .hasMessageContaining("allowed IPs")
        }

    @Test
    fun `marks 503 retryable and 401 not`() {
        assertThat(HandshakeError.fromStatus(503).retryable).isTrue()
        assertThat(HandshakeError.fromStatus(401).retryable).isFalse()
    }

    @Test
    fun `gives up on a handshake nobody answers, and drops the socket`() =
        runTest {
            val s = Setup(this)

            s.advance(HANDSHAKE_TIMEOUT)

            assertThat(failureOf { s.connecting.await() }).isInstanceOf(HandshakeTimeoutError::class.java)
            assertThat(s.socket.terminated).isTrue()
        }

    @Test
    fun `fails the link when the socket refuses a frame, so the next one declares everything`() =
        runTest {
            val s = Setup(this)
            val connection = s.open()

            s.socket.accepts = false
            connection.send("""[{"id":"g1-1"}]""")

            assertThat(s.closes.single().error).hasMessageContaining("refused a frame")
            assertThat(s.socket.terminated).isTrue()
        }
}
