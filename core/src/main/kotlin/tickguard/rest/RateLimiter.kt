package tickguard.rest

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.InstantSource
import kotlin.math.ceil
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

data class RateLimiterStats(
    val waits: Int,
    val totalWait: Duration,
)

/**
 * A token bucket per group, refilled continuously rather than on a timer.
 *
 * Continuous refill means the limiter holds no interval of its own and cannot
 * drift from the clock the server is measuring against. A timer would also
 * release a whole second's worth at once, which is exactly the burst the limit
 * is there to prevent.
 *
 * The limit is re-read on every acquire, because one group is cut during the
 * opening auction and a cached limit would be wrong for those ten minutes.
 *
 * Callers can arrive from any coroutine, so reading and spending a bucket
 * happen under one lock, and waiting happens outside it: a caller that has to
 * wait must not hold up a caller of another group that need not.
 */
class RateLimiter(
    private val clock: InstantSource = InstantSource.system(),
    private val sleep: suspend (Duration) -> Unit = { delay(it) },
) {
    private class Bucket(
        var tokens: Double,
        var updatedAt: Long,
    )

    private val mutex = Mutex()
    private val buckets = LinkedHashMap<RateLimitGroup, Bucket>()
    private var waits = 0
    private var totalWaitMs = 0L

    /** Returns when the call may proceed. */
    suspend fun acquire(group: RateLimitGroup) {
        val wait =
            mutex.withLock {
                val at = clock.millis()
                val bucket = refill(group, at)
                if (bucket.tokens >= 1) {
                    bucket.tokens -= 1
                    return
                }
                val capacity = limitAt(group, Instant.ofEpochMilli(at))
                val waitMs = ceil((1 - bucket.tokens) / capacity * MILLIS_PER_SECOND).toLong()
                waits += 1
                totalWaitMs += waitMs
                waitMs.milliseconds
            }

        sleep(wait)
        // Spend against the refilled bucket rather than assuming the wait was
        // exact; a long timer overshoot should not hand out a free token.
        mutex.withLock { refill(group, clock.millis()).tokens -= 1 }
    }

    /**
     * Feeds the server's own view back in. Headers are the authority: the
     * documented numbers can change without notice.
     *
     * Only ever lowers. The server counts calls we did not make, from another
     * process on the same client, and trusting a higher number would let two
     * of us exceed the limit together.
     */
    suspend fun observe(
        group: RateLimitGroup,
        remaining: Double,
    ) {
        mutex.withLock {
            val bucket = refill(group, clock.millis())
            bucket.tokens = min(bucket.tokens, remaining)
        }
    }

    suspend fun stats(): RateLimiterStats = mutex.withLock { RateLimiterStats(waits, totalWaitMs.milliseconds) }

    private fun refill(
        group: RateLimitGroup,
        at: Long,
    ): Bucket {
        val capacity = limitAt(group, Instant.ofEpochMilli(at)).toDouble()
        val bucket = buckets.getOrPut(group) { Bucket(capacity, at) }
        val gained = (at - bucket.updatedAt) / MILLIS_PER_SECOND * capacity
        bucket.tokens = min(capacity, bucket.tokens + gained)
        bucket.updatedAt = at
        return bucket
    }

    private companion object {
        /** Limits are per second; the clock is in milliseconds. */
        const val MILLIS_PER_SECOND = 1_000.0
    }
}
