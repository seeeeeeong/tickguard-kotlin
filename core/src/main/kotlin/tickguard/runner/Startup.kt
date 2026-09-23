package tickguard.runner

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import tickguard.gateway.BackoffOptions
import tickguard.gateway.DEFAULT_BACKOFF
import tickguard.gateway.nextDelay
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
 * Returns true once [run] succeeds, or false if cancelled first.
 */
@Suppress("TooGenericExceptionCaught") // Any failure of a startup step is retried the same way.
suspend fun retryUntilDone(
    run: suspend () -> Unit,
    onFailure: (error: Exception, attempt: Int, delay: Duration) -> Unit,
    /** Checked between attempts, so a shutdown during a long wait is not held up. */
    isCancelled: () -> Boolean = { false },
    sleep: suspend (Duration) -> Unit = { delay(it) },
    backoff: BackoffOptions = DEFAULT_BACKOFF,
    random: () -> Double = Math::random,
): Boolean {
    var attempt = 0
    while (!isCancelled()) {
        try {
            run()
            return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val wait = nextDelay(attempt, backoff, random)
            onFailure(failure, attempt + 1, wait)
            sleep(wait)
            attempt += 1
        }
    }
    return false
}
