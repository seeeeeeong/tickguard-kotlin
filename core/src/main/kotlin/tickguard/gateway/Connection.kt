package tickguard.gateway

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.time.InstantSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The Toss WebSocket endpoint. */
const val WS_URL = "wss://openapi-ws.tossinvest.com/ws/v1"

/**
 * The keepalive guards a socket once it is open; nothing guarded the opening.
 * A server that accepts TCP and never answers the upgrade — a half-dead load
 * balancer, a NAT mapping dropped mid-handshake — held the supervisor on one
 * attempt with no deadline, and a supervisor waiting on an attempt does not
 * reconnect.
 */
val HANDSHAKE_TIMEOUT = 15.seconds

/**
 * What a transport must offer. The OkHttp adapter is the real one; tests drive
 * a fake by hand.
 */
interface Socket {
    /** False when the frame was not queued: the socket is closing, or its buffer is full. */
    fun send(payload: String): Boolean

    fun close()

    /**
     * Destroys the socket without a closing handshake. For a link already
     * judged dead: close() would send a close frame into it and hold the
     * socket for the close timeout, waiting for a reply that cannot come.
     */
    fun terminate()
}

/** Called from the transport's own threads, possibly several of them. */
interface SocketEvents {
    fun onOpen()

    fun onMessage(raw: String)

    fun onClosed(code: Int)

    fun onFailure(error: Throwable)

    /** The upgrade was answered with something other than 101. */
    fun onHandshakeFailed(status: Int)
}

fun interface SocketFactory {
    fun open(
        url: String,
        accessToken: String,
        handshakeTimeout: Duration,
        events: SocketEvents,
    ): Socket
}

data class ConnectionClosed(
    val code: Int?,
    val error: Throwable? = null,
)

interface ConnectionHandlers {
    /**
     * Called on the socket's reader thread. It must only hand the frame on —
     * the order channel is closed after two seconds of stalled consumption.
     */
    fun onFrame(frame: ServerFrame)

    /** Fires exactly once, for any reason: server close, transport error, or [Connection.close]. */
    fun onClosed(reason: ConnectionClosed)
}

interface Connection {
    /** Sends a raw text frame. Subscription declarations and PING both go through here. */
    fun send(payload: String)

    fun close()
}

class ConnectOptions(
    val accessToken: String,
    val handlers: ConnectionHandlers,
    val sockets: SocketFactory,
    val clock: InstantSource,
    /** Where the keepalive runs: off the engine, so a busy engine cannot fake a dead link. */
    val keepaliveScope: CoroutineScope,
    val pingInterval: Duration = PING_INTERVAL,
    /** Overridden only to point at a local server in tests. */
    val url: String = WS_URL,
    val handshakeTimeout: Duration = HANDSHAKE_TIMEOUT,
)

/**
 * Returns once the socket is open, so a caller can declare subscriptions
 * knowing the server is listening. Handshake rejections (401/403/503) throw
 * here rather than arriving later as an error frame, because they are HTTP
 * statuses and there is no connection to report them on.
 */
suspend fun connect(options: ConnectOptions): Connection {
    val link = Link(options)
    val socket = options.sockets.open(options.url, options.accessToken, options.handshakeTimeout, link)
    link.socket = socket

    try {
        return withTimeout(options.handshakeTimeout) { link.opened.await() }
    } catch (_: TimeoutCancellationException) {
        socket.terminate()
        throw HandshakeTimeoutError(options.handshakeTimeout)
    }
}

/**
 * One socket's lifecycle. Its events arrive on the transport's reader and
 * writer threads and on the keepalive's, so every transition is taken under
 * one lock, and closing happens once however many of them report it.
 */
private class Link(
    private val options: ConnectOptions,
) : SocketEvents,
    Connection {
    val opened = CompletableDeferred<Connection>()
    lateinit var socket: Socket

    private val lock = Any()
    private var finished = false
    private var keepalive: Keepalive? = null

    override fun onOpen() {
        synchronized(lock) {
            if (finished) return
            keepalive =
                startKeepalive(
                    scope = options.keepaliveScope,
                    clock = options.clock,
                    send = ::send,
                    interval = options.pingInterval,
                    onUnresponsive = { silent ->
                        // Writes still succeed into a socket that delivers nothing, so
                        // the transport will not report this. Closing it is what turns
                        // an invisible outage into a reconnect.
                        finish(ConnectionClosed(null, IOException("Connection silent for ${silent.inWholeSeconds}s")))
                        socket.terminate()
                    },
                )
            opened.complete(this)
        }
    }

    override fun onMessage(raw: String) {
        val frame = parseFrame(raw)
        if (frame is ServerFrame.Pong) keepalive?.pong()
        options.handlers.onFrame(frame)
    }

    override fun onClosed(code: Int) = finish(ConnectionClosed(code))

    override fun onFailure(error: Throwable) = finish(ConnectionClosed(null, error))

    override fun onHandshakeFailed(status: Int) = finish(ConnectionClosed(null, HandshakeError.fromStatus(status)))

    override fun send(payload: String) {
        if (socket.send(payload)) return
        // A frame that was not queued is a declaration the server never sees,
        // and with full-replace subscriptions that leaves it serving an old set
        // without a word. Failing the link makes the next one declare it all.
        finish(ConnectionClosed(null, IOException("Socket refused a frame: closing, or its buffer is full")))
        socket.terminate()
    }

    override fun close() = socket.close()

    /**
     * Whether the link had opened is decided under the same lock that opens
     * it. Deciding outside it would let an open land between the check and the
     * failure, and a caller then holds a connection whose close is never reported.
     */
    private fun finish(reason: ConnectionClosed) {
        val wasOpen =
            synchronized(lock) {
                if (finished) return
                finished = true
                keepalive?.stop()
                opened.isCompleted ||
                    !opened.completeExceptionally(
                        reason.error ?: IOException("Connection closed before opening (code ${reason.code})"),
                    )
            }
        if (wasOpen) options.handlers.onClosed(reason)
    }
}
