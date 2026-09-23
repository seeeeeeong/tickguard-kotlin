package tickguard.gateway

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.InstantSource
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The server closes a connection after 180s without inbound data from us, and
 * the frames it sends do NOT reset that timer. So a socket streaming thousands
 * of ticks a minute still dies at 180s unless we send something.
 *
 * The payload is the bare text `PING`, not JSON. Wrapping it in an object gets
 * no `pong` back, which fails the same silent way as sending nothing at all.
 * A WebSocket protocol-level ping frame is not a substitute either, which is
 * why OkHttp's own pingInterval stays off.
 *
 * Sending is not enough on its own. A first live run lost its network for over
 * an hour while the socket reported itself open: writes kept succeeding into a
 * connection that delivered nothing, and no error was ever raised. So pings are
 * checked against pongs, and a connection that stops answering is declared
 * dead regardless of what its write path claims.
 *
 * Liveness is measured in wall-clock time, not in missed ticks. A counter of
 * unanswered pings counts *timer firings*, and a timer does not fire on
 * schedule: a long GC pause or a host that suspends collapses many missed
 * intervals into a single late wake-up. The server's own 180s deadline is
 * wall-clock, so this has to be too — and a monotonic clock will not do, since
 * it stops while the host is suspended and the server's clock does not.
 *
 * Checking is therefore decoupled from sending: the clock is read four times
 * per ping, so a dead connection is noticed within a check of its deadline.
 *
 * Runs in its own scope rather than on the engine: a busy engine must not delay
 * the check into a false alarm, and a pong is recorded from the socket's thread.
 */
class Keepalive internal constructor(
    private val clock: InstantSource,
) {
    private val lastPong = AtomicLong(clock.millis())
    internal var job: Job? = null

    /** Every inbound pong restarts the clock. Safe from any thread. */
    fun pong() = lastPong.set(clock.millis())

    fun stop() {
        job?.cancel()
    }

    /** Time since the last pong, or since the connection opened. */
    fun silentFor(): Duration = (clock.millis() - lastPong.get()).milliseconds

    internal fun lastPongAt(): Long = lastPong.get()
}

/** The bare text Toss answers with `pong`. */
const val PING_PAYLOAD = "PING"

/**
 * Two jobs share this interval, and the tighter one sets it. Keeping the
 * server's 180s idle timer at bay needs a ping well inside 180s; noticing a
 * dead link needs one often enough that silence is short. At 60s the first
 * was met but a connection a router's NAT had dropped went unnoticed for up
 * to 135s. binance's client pings every 10s with a 5s pong deadline; 20s
 * with two misses is the middle ground: a dead link is noticed in about 45s,
 * at three tiny frames a minute.
 */
val PING_INTERVAL = 20.seconds

/** 40s of silence. Detection lands near 45s — well inside the server's 180s. */
const val MAX_MISSED_PONGS = 2

/**
 * The clock is read this many times per ping interval. A ratio rather than a
 * fixed period: the deadline scales with the ping interval, so a fixed check
 * period longer than a short deadline would declare a healthy connection dead
 * before its first ping went out. The original's probe hit exactly that.
 */
const val CHECKS_PER_PING = 4

fun startKeepalive(
    scope: CoroutineScope,
    clock: InstantSource,
    send: (String) -> Unit,
    /** Called once, when the answer gap passes the deadline. The connection is dead. */
    onUnresponsive: (Duration) -> Unit,
    interval: Duration = PING_INTERVAL,
    maxMissed: Int = MAX_MISSED_PONGS,
): Keepalive {
    val keepalive = Keepalive(clock)
    val deadline = (interval * maxMissed).inWholeMilliseconds
    val intervalMs = interval.inWholeMilliseconds

    keepalive.job =
        scope.launch {
            // A connection that never answers its first ping has to die too, so
            // the clock starts at open rather than waiting for a pong.
            var lastPing = clock.millis()
            while (true) {
                delay(interval / CHECKS_PER_PING)
                val at = clock.millis()
                val silent = at - keepalive.lastPongAt()
                if (silent > deadline) {
                    // Reported once. Writing another ping into a dead socket
                    // succeeds and teaches us nothing, so sending stops here.
                    onUnresponsive(silent.milliseconds)
                    return@launch
                }
                if (at - lastPing < intervalMs) continue
                lastPing = at
                send(PING_PAYLOAD)
            }
        }
    return keepalive
}
