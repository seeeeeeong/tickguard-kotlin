package tickguard.toss.gateway

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.WebSocketListener
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tickguard.gateway.ConnectOptions
import tickguard.gateway.ConnectionClosed
import tickguard.gateway.ConnectionHandlers
import tickguard.gateway.HandshakeTimeoutError
import tickguard.gateway.ServerFrame
import tickguard.gateway.SupervisorOptions
import tickguard.gateway.connect
import tickguard.gateway.startSupervisor
import tickguard.testing.failureOf
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.time.InstantSource
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import java.net.Socket as TcpSocket

/**
 * Against real sockets rather than a fake one. The core tests prove the policy;
 * these prove OkHttp surfaces each situation the way the policy expects.
 */
class OkHttpSocketFactoryIntegrationTest {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val closeables = mutableListOf<AutoCloseable>()
    private val sockets = OkHttpSocketFactory(OkHttpClient())

    @AfterEach
    fun tearDown() {
        scope.cancel()
        closeables.forEach { it.close() }
    }

    private val quiet =
        object : ConnectionHandlers {
            override fun onFrame(frame: ServerFrame) = Unit

            override fun onClosed(reason: ConnectionClosed) = Unit
        }

    private fun options(
        url: String,
        token: String = "token",
        handlers: ConnectionHandlers = quiet,
        pingInterval: Duration = 20.seconds,
        handshakeTimeout: Duration = 15.seconds,
    ) = ConnectOptions(
        accessToken = token,
        handlers = handlers,
        sockets = sockets,
        clock = InstantSource.system(),
        keepaliveScope = scope,
        pingInterval = pingInterval,
        url = url,
        handshakeTimeout = handshakeTimeout,
    )

    private suspend fun eventually(
        within: Duration = 5.seconds,
        condition: () -> Boolean,
    ) = withTimeout(within) { while (!condition()) delay(10) }

    @Test
    fun `rides out a 403 and reconnects on its own once the IP is allowed`() =
        runTest {
            withContext(Dispatchers.Default) {
                var allow = false
                val accepted = CopyOnWriteArrayList<String>()
                val server = MockWebServer().also { closeables += it }
                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse {
                            if (!allow) return MockResponse.Builder().code(403).build()
                            accepted += request.headers["Authorization"].orEmpty()
                            return MockResponse.Builder().webSocketUpgrade(ClosesWhenAsked).build()
                        }
                    }
                server.start()
                val url = server.url("/ws").toString().replaceFirst("http", "ws")
                val blocked = CopyOnWriteArrayList<Throwable>()
                val unblocked = CopyOnWriteArrayList<Duration>()
                val gaveUp = CopyOnWriteArrayList<Throwable>()

                val supervisor =
                    startSupervisor(
                        scope,
                        SupervisorOptions(
                            getToken = { "token" },
                            invalidateToken = {},
                            connect = { token, handlers -> connect(options(url, token, handlers)) },
                            onOpen = {},
                            onFrame = {},
                            onGaveUp = { gaveUp += it },
                            onBlocked = { blocked += it },
                            onUnblocked = { unblocked += it },
                            blockedRetry = 50.milliseconds,
                        ),
                    )

                eventually { blocked.size == 1 }
                // Several refused retries go by before anyone registers the IP.
                delay(200)
                assertThat(blocked).hasSize(1)
                assertThat(accepted).isEmpty()

                allow = true
                eventually { unblocked.size == 1 }

                assertThat(accepted).containsExactly("Bearer token")
                assertThat(gaveUp).isEmpty()
                supervisor.stop()
            }
        }

    @Test
    fun `releases a refused socket even when the server keeps the connection open`() =
        runTest {
            withContext(Dispatchers.Default) {
                // The original's ws client did not abort a handshake handed to its
                // 'unexpected-response' listener; against a server that answers 403
                // and keeps the connection alive it held one socket per retry, and a
                // test counted 57. OkHttp may reuse such a connection or close it;
                // either is fine, holding more than one open is not. Counted from
                // the server: sockets opened that the client has not ended.
                val requests = AtomicInteger()
                val accepted = AtomicInteger()
                val endedByClient = AtomicInteger()
                val server =
                    RawServer { socket ->
                        accepted.incrementAndGet()
                        while (socket.readRequestHead().isNotEmpty()) {
                            requests.incrementAndGet()
                            socket.getOutputStream().write(
                                "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: keep-alive\r\n\r\n"
                                    .toByteArray(),
                            )
                        }
                        endedByClient.incrementAndGet()
                    }.also { closeables += it }
                val blocked = CopyOnWriteArrayList<Throwable>()

                val supervisor =
                    startSupervisor(
                        scope,
                        SupervisorOptions(
                            getToken = { "token" },
                            invalidateToken = {},
                            connect = { token, handlers -> connect(options(server.url, token, handlers)) },
                            onOpen = {},
                            onFrame = {},
                            onGaveUp = {},
                            onBlocked = { blocked += it },
                            blockedRetry = 20.milliseconds,
                        ),
                    )

                eventually { blocked.size == 1 }
                // Several retries, however long a loaded machine takes to make them:
                // a fixed wait here failed when the whole build ran at once.
                eventually { requests.get() > 3 }
                supervisor.stop()
                // The attempt in flight at stop() may not have ended yet.
                eventually { accepted.get() - endedByClient.get() <= 1 }
            }
        }

    @Test
    fun `times out a handshake nobody answers instead of holding the supervisor forever`() =
        runTest {
            withContext(Dispatchers.Default) {
                // Accepts TCP and says nothing: a half-dead balancer, or a NAT
                // mapping dropped mid-handshake.
                val server = RawServer { it.awaitEnd() }.also { closeables += it }
                val started = TimeSource.Monotonic.markNow()

                val failure = failureOf { connect(options(server.url, handshakeTimeout = 100.milliseconds)) }

                assertThat(failure).isInstanceOf(HandshakeTimeoutError::class.java)
                assertThat(started.elapsedNow()).isLessThan(2.seconds)
            }
        }

    @Test
    fun `notices a link that goes dead after the upgrade, and tears it down at once`() =
        runTest {
            withContext(Dispatchers.Default) {
                // Upgrades like a real server, then answers nothing: not pings, not a
                // close frame. That is a NAT mapping dropped mid-session. A close
                // handshake into it would hold the socket for the close timeout.
                val serverSawEnd = AtomicReference<Boolean>(false)
                val server =
                    RawServer { socket ->
                        val key =
                            Regex("Sec-WebSocket-Key: (.+)\r\n", RegexOption.IGNORE_CASE)
                                .find(socket.readRequestHead())
                                ?.groupValues
                                ?.get(1)
                                .orEmpty()
                        socket.getOutputStream().write(
                            (
                                "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                                    "Sec-WebSocket-Accept: ${acceptFor(key)}\r\n\r\n"
                            ).toByteArray(),
                        )
                        socket.awaitEnd()
                        serverSawEnd.set(true)
                    }.also { closeables += it }
                val closed = AtomicReference<ConnectionClosed?>(null)
                val handlers =
                    object : ConnectionHandlers {
                        override fun onFrame(frame: ServerFrame) = Unit

                        override fun onClosed(reason: ConnectionClosed) = closed.set(reason)
                    }

                connect(options(server.url, handlers = handlers, pingInterval = 100.milliseconds))

                eventually(2.seconds) { closed.get() != null }
                assertThat(closed.get()?.error).hasMessageContaining("silent for")
                // The socket itself is gone within a second, not after a close timeout.
                eventually(1.seconds) { serverSawEnd.get() }
            }
        }

    /** Answers a close, so the server can shut down once the client leaves. */
    private object ClosesWhenAsked : WebSocketListener() {
        override fun onClosing(
            webSocket: okhttp3.WebSocket,
            code: Int,
            reason: String,
        ) {
            webSocket.close(code, null)
        }
    }

    /** A TCP server that hands each accepted socket to [serve] on its own thread. */
    private class RawServer(
        serve: (TcpSocket) -> Unit,
    ) : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        private val accepted = CopyOnWriteArrayList<TcpSocket>()
        val url = "ws://127.0.0.1:${server.localPort}/ws"

        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    accepted += socket
                    thread(isDaemon = true) { runCatching { serve(socket) } }
                }
            }
        }

        override fun close() {
            server.close()
            accepted.forEach { runCatching { it.close() } }
        }
    }
}

/** The next request's head, or empty once the client ends the connection. */
private fun TcpSocket.readRequestHead(): String {
    val head = StringBuilder()
    val input = getInputStream()
    while (!head.endsWith("\r\n\r\n")) {
        val byte =
            try {
                input.read()
            } catch (_: IOException) {
                -1
            }
        if (byte < 0) return ""
        head.append(byte.toChar())
    }
    return head.toString()
}

/** Blocks until the client ends the connection. */
private fun TcpSocket.awaitEnd() {
    try {
        while (getInputStream().read() >= 0) continue
    } catch (_: IOException) {
        // Reset by the client: ended all the same.
    }
}

private fun acceptFor(key: String): String =
    Base64.getEncoder().encodeToString(
        MessageDigest.getInstance("SHA-1").digest("${key}258EAFA5-E914-47DA-95CA-C5AB0DC85B11".toByteArray()),
    )
