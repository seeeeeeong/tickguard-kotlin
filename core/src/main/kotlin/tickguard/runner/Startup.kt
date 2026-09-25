package tickguard.runner

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import tickguard.gateway.BLOCKED_RETRY
import tickguard.gateway.BackoffOptions
import tickguard.gateway.DEFAULT_BACKOFF
import tickguard.gateway.nextDelay
import tickguard.network.isSourceIpRejected
import kotlin.time.Duration

/**
 * Runs a startup step until it succeeds, instead of letting the first failure
 * end the process.
 *
 * After a power cut the server and the router boot together, and a small PC
 * is usually up first. For the minute or two until the router has a WAN link,
 * every lookup fails with a DNS error. Exiting on that hands the problem to
 * the container's restart policy, which answers with a crash loop that ends
 * only when the network happens to be back at the moment of a restart.
 *
 * An unregistered source IP is different: it lasts until a person registers
 * it, so it is retried at the stream's fixed blocked cadence rather than
 * backed off, and the stream has already alerted it. Each failure says
 * whether it repeats the one before, so a caller can report a block once
 * instead of every thirty seconds for as long as it lasts.
 *
 * Returns true once [run] succeeds, or false if cancelled first.
 */
@Suppress("TooGenericExceptionCaught") // Any failure of a startup step is retried the same way.
suspend fun retryUntilDone(
    run: suspend () -> Unit,
    onFailure: (error: Exception, attempt: Int, delay: Duration, repeated: Boolean) -> Unit,
    /** Checked between attempts, so a shutdown during a long wait is not held up. */
    isCancelled: () -> Boolean = { false },
    sleep: suspend (Duration) -> Unit = { delay(it) },
    backoff: BackoffOptions = DEFAULT_BACKOFF,
    random: () -> Double = Math::random,
    blockedRetry: Duration = BLOCKED_RETRY,
): Boolean {
    var attempt = 0
    var previous: Exception? = null
    while (!isCancelled()) {
        try {
            run()
            return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val wait = if (isSourceIpRejected(failure)) blockedRetry else nextDelay(attempt, backoff, random)
            val repeated = previous?.let { it.javaClass == failure.javaClass && it.message == failure.message } ?: false
            onFailure(failure, attempt + 1, wait, repeated)
            previous = failure
            sleep(wait)
            attempt += 1
        }
    }
    return false
}
