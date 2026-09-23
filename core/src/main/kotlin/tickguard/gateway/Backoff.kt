package tickguard.gateway

import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Equal jitter: half the ceiling, plus a random half.
 *
 * Jitter matters more here than it looks. `server-shutdown` goes out to every
 * client at once during a Toss deploy, so a fixed delay would have all of them
 * reconnect on the same tick. The guaranteed floor keeps a flapping server from
 * turning into a tight retry loop, which full jitter would allow by rolling
 * near zero.
 */
data class BackoffOptions(
    val base: Duration,
    val cap: Duration,
)

/** One second doubling to thirty: fast after a blip, patient through an outage. */
val DEFAULT_BACKOFF = BackoffOptions(base = 1.seconds, cap = 30.seconds)

fun nextDelay(
    attempt: Int,
    options: BackoffOptions = DEFAULT_BACKOFF,
    random: () -> Double = Math::random,
): Duration {
    val grown = options.base.inWholeMilliseconds * 2.0.pow(attempt.coerceAtLeast(0))
    val ceiling = min(options.cap.inWholeMilliseconds.toDouble(), grown)
    val half = ceiling / 2
    return (half + random() * half).roundToLong().milliseconds
}
