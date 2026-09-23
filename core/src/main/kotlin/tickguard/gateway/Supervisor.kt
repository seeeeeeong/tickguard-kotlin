package tickguard.gateway

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tickguard.network.isSourceIpRejected
import java.time.InstantSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** A connection that stayed up this long is treated as healthy; backoff resets. */
val HEALTHY_AFTER = 30.seconds

/**
 * How often a blocked connection is retried. The fix is a person adding the
 * IP in WTS, usually from a phone; once they have, the stream should come back
 * within a minute without anyone logging into the server. One handshake a
 * minute costs the server nothing.
 */
val BLOCKED_RETRY = 60.seconds

enum class FailureAction { RETRY, RETRY_WITH_FRESH_TOKEN, BLOCKED, GIVE_UP }

/**
 * The retry policy, kept apart from the loop that applies it so it can be read
 * and tested on its own.
 *
 * A 401 can mean the token was revoked by an issue elsewhere, which our own
 * expiry cannot see, so it is worth exactly one attempt with a fresh token.
 * Beyond that the credentials themselves are wrong and retrying only hides it.
 *
 * A 403 repeats identically until the source IP is registered — but that is a
 * change a person makes from a phone, not a redeploy, so it is retried slowly
 * rather than given up on. A home connection's IP changes without warning,
 * and giving up turned every change into a trip to the server to restart it.
 */
fun classifyFailure(
    error: Throwable,
    usedFreshTokenRetry: Boolean,
): FailureAction =
    when {
        isSourceIpRejected(error) -> FailureAction.BLOCKED
        error !is HandshakeError || error.retryable -> FailureAction.RETRY
        error.status == 401 && !usedFreshTokenRetry -> FailureAction.RETRY_WITH_FRESH_TOKEN
        else -> FailureAction.GIVE_UP
    }

class SupervisorOptions(
    val getToken: suspend () -> String,
    /** Called after a 401 with the token that was refused, so the next attempt does not present it again. */
    val invalidateToken: suspend (String) -> Unit,
    /** Opens one connection. The supervisor supplies the token and the handlers. */
    val connect: suspend (accessToken: String, handlers: ConnectionHandlers) -> Connection,
    /** Fires on every successful connection, on the supervisor's scope. Declare subscriptions here. */
    val onOpen: (Connection) -> Unit,
    /** Called on the socket's reader thread, for the current connection's frames only. Hand them on; do no work. */
    val onFrame: (ServerFrame) -> Unit,
    /** Terminal: the failure will repeat until configuration changes. No further attempts. */
    val onGaveUp: (Throwable) -> Unit,
    /**
     * The source IP was refused. Called once per outage, not per retry;
     * retrying continues at [blockedRetry] until the IP is registered.
     */
    val onBlocked: (Throwable) -> Unit = {},
    /** The first connection after a block, with how long it lasted. */
    val onUnblocked: (Duration) -> Unit = {},
    val clock: InstantSource = InstantSource.system(),
    val random: () -> Double = Math::random,
    val backoff: BackoffOptions = DEFAULT_BACKOFF,
    val healthyAfter: Duration = HEALTHY_AFTER,
    val blockedRetry: Duration = BLOCKED_RETRY,
)

/**
 * Keeps one connection alive across closes.
 *
 * Reconnecting is not enough on its own: a new connection starts with no
 * subscriptions, because declarations are full-replace and belong to the socket
 * that made them. Events from the gap are never redelivered either. So `onOpen`
 * fires on every successful connection, not just the first — it is the only
 * place to re-declare, which makes forgetting to structurally impossible.
 */
class Supervisor internal constructor(
    private val options: SupervisorOptions,
) {
    private var job: Job? = null
    private var attempt = 0
    private var blockedSince: Long? = null
    private var usedFreshTokenRetry = false

    /**
     * Bumped per connection. A frame read from a socket that is no longer the
     * current one — its reader thread can still be draining after the close was
     * reported — is dropped rather than mixed into the next connection's stream.
     */
    @Volatile private var generation = 0

    @Volatile private var current: Connection? = null

    /** Closes the current connection and stops reconnecting. Idempotent. */
    fun stop() {
        job?.cancel()
        current?.close()
    }

    internal fun start(scope: CoroutineScope) {
        job = scope.launch { supervise() }
    }

    private suspend fun supervise() {
        while (true) {
            val wait = afterAttempt(attemptConnection()) ?: return
            delay(wait)
            attempt += 1
        }
    }

    /** Null on a connection that opened and later closed; the failure otherwise. */
    @Suppress("TooGenericExceptionCaught") // Every failure is classified; none may end the loop unread.
    private suspend fun attemptConnection(): Throwable? =
        try {
            runOneConnection()
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failure
        }

    /** Returns when the connection closes, for any reason. */
    private suspend fun runOneConnection() {
        val token = options.getToken()
        lastToken = token
        val closed = CompletableDeferred<Unit>()
        val mine = ++generation

        val connection =
            options.connect(
                token,
                object : ConnectionHandlers {
                    override fun onFrame(frame: ServerFrame) {
                        if (mine == generation) options.onFrame(frame)
                    }

                    override fun onClosed(reason: ConnectionClosed) {
                        closed.complete(Unit)
                    }
                },
            )

        val openedAt = options.clock.millis()
        current = connection
        try {
            options.onOpen(connection)
            blockedSince?.let { since ->
                blockedSince = null
                options.onUnblocked((openedAt - since).milliseconds)
            }
            closed.await()
        } finally {
            current = null
            if (!closed.isCompleted) connection.close()
        }

        // Only a connection that lasted resets the backoff. Without this, a server
        // that accepts and immediately drops turns into a tight reconnect loop.
        if ((options.clock.millis() - openedAt).milliseconds >= options.healthyAfter) attempt = 0
    }

    private var lastToken: String? = null

    /** Reads one attempt's outcome. Returns the wait, or null to stop. */
    private suspend fun afterAttempt(failure: Throwable?): Duration? {
        if (failure == null) {
            usedFreshTokenRetry = false
            return nextDelay(attempt, options.backoff, options.random)
        }
        val action = classifyFailure(failure, usedFreshTokenRetry)
        if (action == FailureAction.RETRY_WITH_FRESH_TOKEN) usedFreshTokenRetry = true
        return respondTo(action, failure)
    }

    /** Applies one failure's consequences. Returns the wait, or null to stop. */
    private suspend fun respondTo(
        action: FailureAction,
        failure: Throwable,
    ): Duration? =
        when (action) {
            FailureAction.GIVE_UP -> {
                options.onGaveUp(failure)
                null
            }

            FailureAction.BLOCKED -> {
                if (blockedSince == null) {
                    blockedSince = options.clock.millis()
                    options.onBlocked(failure)
                }
                // Fixed, not backed off: the wait is for a person, and backoff would
                // stretch "registered it, why is it still down" to many minutes.
                options.blockedRetry
            }

            FailureAction.RETRY_WITH_FRESH_TOKEN -> {
                lastToken?.let { options.invalidateToken(it) }
                nextDelay(attempt, options.backoff, options.random)
            }

            FailureAction.RETRY -> {
                nextDelay(attempt, options.backoff, options.random)
            }
        }
}

/** Starts supervising in [scope], whose dispatcher runs onOpen and every callback but onFrame. */
fun startSupervisor(
    scope: CoroutineScope,
    options: SupervisorOptions,
): Supervisor = Supervisor(options).also { it.start(scope) }
