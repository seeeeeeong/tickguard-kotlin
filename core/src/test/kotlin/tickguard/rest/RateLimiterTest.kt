package tickguard.rest

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.InstantSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RateLimiterTest {
    /** A clock that only moves when a sleep asks it to, so waits are exact. 14:00 KST, outside the auction. */
    private var now = Instant.parse("2026-09-23T05:00:00Z").toEpochMilli()
    private val slept = mutableListOf<Duration>()
    private val limiter =
        RateLimiter(
            clock = InstantSource { Instant.ofEpochMilli(now) },
            sleep = {
                slept += it
                now += it.inWholeMilliseconds
            },
        )

    @Test
    fun `lets a full bucket through without waiting`() =
        runTest {
            // ACCOUNT allows 1/s, so one call is free and the bucket is then empty.
            limiter.acquire(RateLimitGroup.ACCOUNT)

            assertThat(slept).isEmpty()
        }

    @Test
    fun `waits out the refill rather than exceeding the limit`() =
        runTest {
            limiter.acquire(RateLimitGroup.ACCOUNT)
            limiter.acquire(RateLimitGroup.ACCOUNT)

            assertThat(slept).containsExactly(1.seconds)
        }

    @Test
    fun `refills continuously, so waiting half a second buys half a token`() =
        runTest {
            limiter.acquire(RateLimitGroup.ACCOUNT)
            now += 500
            limiter.acquire(RateLimitGroup.ACCOUNT)

            assertThat(slept).containsExactly(500.milliseconds)
        }

    @Test
    fun `keeps groups independent`() =
        runTest {
            limiter.acquire(RateLimitGroup.ACCOUNT)
            limiter.acquire(RateLimitGroup.MARKET_DATA)

            assertThat(slept).isEmpty()
        }

    @Test
    fun `lowers the bucket to what the server reports`() =
        runTest {
            // MARKET_DATA allows 15/s, but the server says only one is left.
            limiter.observe(RateLimitGroup.MARKET_DATA, 1.0)
            limiter.acquire(RateLimitGroup.MARKET_DATA)
            limiter.acquire(RateLimitGroup.MARKET_DATA)

            assertThat(slept).hasSize(1)
        }

    @Test
    fun `never raises the bucket on a report`() =
        runTest {
            limiter.acquire(RateLimitGroup.ACCOUNT)
            // Another process on the same client may have spent what this says is free.
            limiter.observe(RateLimitGroup.ACCOUNT, 999.0)
            limiter.acquire(RateLimitGroup.ACCOUNT)

            assertThat(slept).containsExactly(1.seconds)
        }

    @Test
    fun `counts what it cost`() =
        runTest {
            limiter.acquire(RateLimitGroup.ACCOUNT)
            limiter.acquire(RateLimitGroup.ACCOUNT)

            assertThat(limiter.stats()).isEqualTo(RateLimiterStats(1, 1.seconds))
        }

    @Test
    fun `cuts a full bucket down to the peak limit when the auction opens`() =
        runTest {
            // A bucket full at the normal 6/s must not spend six at 09:00:00.
            now = Instant.parse("2026-09-22T23:59:59Z").toEpochMilli()
            limiter.acquire(RateLimitGroup.ORDER_INFO)
            now = Instant.parse("2026-09-23T00:00:00Z").toEpochMilli()

            // Three pass at the peak's 3/s; without the cut, five would still be banked.
            repeat(3) { limiter.acquire(RateLimitGroup.ORDER_INFO) }
            assertThat(slept).isEmpty()

            limiter.acquire(RateLimitGroup.ORDER_INFO)
            assertThat(slept).hasSize(1)
        }
}
