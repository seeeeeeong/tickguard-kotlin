package tickguard.gateway

import tickguard.network.StatusFailure
import java.io.IOException
import kotlin.time.Duration

/**
 * Handshake rejections are HTTP statuses, not frames — there is no connection
 * yet to report them on. All three are configuration problems, so the messages
 * name the fix instead of inviting a retry.
 */
class HandshakeError(
    override val status: Int,
    message: String,
    /** Only 503 is worth retrying; the rest fail identically until something changes. */
    val retryable: Boolean,
) : IOException(message),
    StatusFailure {
    companion object {
        fun fromStatus(status: Int): HandshakeError =
            when (status) {
                401 -> {
                    HandshakeError(
                        status,
                        "WebSocket handshake rejected: the access token is missing, invalid, or expired. " +
                            "Issue a new one and reconnect.",
                        retryable = false,
                    )
                }

                403 -> {
                    HandshakeError(
                        status,
                        "WebSocket handshake rejected: this source IP is not registered. Add it under " +
                            "WTS > Settings > Open API > allowed IPs. The same list applies to REST.",
                        retryable = false,
                    )
                }

                503 -> {
                    HandshakeError(status, "Toss returned 503 during the handshake.", retryable = true)
                }

                else -> {
                    HandshakeError(status, "WebSocket handshake failed with $status.", retryable = false)
                }
            }
    }
}

/** A server that accepts TCP and never answers the upgrade. Transient, so retried. */
class HandshakeTimeoutError(
    timeout: Duration,
) : IOException("Opening handshake has timed out after $timeout")
