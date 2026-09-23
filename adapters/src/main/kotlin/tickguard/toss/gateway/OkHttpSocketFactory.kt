package tickguard.toss.gateway

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import tickguard.gateway.Socket
import tickguard.gateway.SocketEvents
import tickguard.gateway.SocketFactory
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * The Toss socket over OkHttp.
 *
 * OkHttp retries and follows on its own unless told not to: a 503 with
 * `Retry-After: 0`, a 408 and a failed connection are each retried inside the
 * call, and redirects are followed. The supervisor owns the retry policy, and a
 * second one hidden here would retry a refusal it has been told to wait out.
 *
 * Its protocol-level ping stays off. Toss wants the bare text `PING`, and
 * whether it answers a ping frame at all is not documented; if it does not,
 * OkHttp would kill a healthy connection for the missing pong.
 */
class OkHttpSocketFactory(
    client: OkHttpClient,
) : SocketFactory {
    private val base =
        client
            .newBuilder()
            .pingInterval(java.time.Duration.ZERO)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

    override fun open(
        url: String,
        accessToken: String,
        handshakeTimeout: Duration,
        events: SocketEvents,
    ): Socket {
        // The handshake deadline belongs to the caller, which reports it as one.
        // These are a backstop behind it, set later so the two never race:
        // callTimeout covers the upgrade and is released once it succeeds, and
        // the read timeout, after the upgrade, applies only inside a frame.
        val backstop = (handshakeTimeout + BACKSTOP_MARGIN).toJavaDuration()
        val client =
            base
                .newBuilder()
                .callTimeout(backstop)
                .readTimeout(backstop)
                .build()
        val request =
            Request
                .Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .build()

        return OkHttpSocket(client.newWebSocket(request, Listener(events)))
    }
}

private class OkHttpSocket(
    private val ws: WebSocket,
) : Socket {
    override fun send(payload: String): Boolean = ws.send(payload)

    override fun close() {
        ws.close(NORMAL_CLOSURE, null)
    }

    override fun terminate() = ws.cancel()
}

/**
 * Callbacks come from OkHttp's reader thread (open, messages, closing) and its
 * writer thread (failures while writing), so this holds no state of its own
 * beyond whether the upgrade succeeded.
 */
private class Listener(
    private val events: SocketEvents,
) : WebSocketListener() {
    private val opened = AtomicBoolean(false)

    override fun onOpen(
        webSocket: WebSocket,
        response: Response,
    ) {
        opened.set(true)
        events.onOpen()
    }

    override fun onMessage(
        webSocket: WebSocket,
        text: String,
    ) = events.onMessage(text)

    override fun onMessage(
        webSocket: WebSocket,
        bytes: ByteString,
    ) = events.onMessage(bytes.utf8())

    /**
     * OkHttp does not answer a close frame by itself. The server is going away
     * either way, so the socket is torn down at once rather than held for a
     * close handshake.
     */
    override fun onClosing(
        webSocket: WebSocket,
        code: Int,
        reason: String,
    ) {
        events.onClosed(code)
        webSocket.cancel()
    }

    override fun onClosed(
        webSocket: WebSocket,
        code: Int,
        reason: String,
    ) = events.onClosed(code)

    /**
     * A non-101 answer to the upgrade arrives here with its response. OkHttp
     * closes that response and releases the connection once this returns, so
     * the status is read now; nothing keeps the socket open behind it.
     */
    override fun onFailure(
        webSocket: WebSocket,
        t: Throwable,
        response: Response?,
    ) {
        if (response != null && !opened.get()) {
            events.onHandshakeFailed(response.code)
        } else {
            events.onFailure(t)
        }
    }
}

/** Long enough that the caller's own deadline always fires first. */
private val BACKSTOP_MARGIN = 5.seconds

/** RFC 6455 1000: the purpose the connection was established for is fulfilled. */
private const val NORMAL_CLOSURE = 1000
