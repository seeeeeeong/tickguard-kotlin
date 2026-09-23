package tickguard.testing

import kotlinx.coroutines.test.TestCoroutineScheduler
import java.time.Instant
import java.time.InstantSource

/**
 * Wall-clock time that moves with the test scheduler's virtual time, plus a
 * [skew] a test can add to model time that passed without any timer firing —
 * a GC pause, a suspended host — which is exactly what wall-clock liveness
 * exists to catch.
 */
class VirtualClock(
    private val scheduler: TestCoroutineScheduler,
    private val epoch: Long = 0,
) : InstantSource {
    var skew = 0L

    override fun instant(): Instant = Instant.ofEpochMilli(epoch + scheduler.currentTime + skew)
}
