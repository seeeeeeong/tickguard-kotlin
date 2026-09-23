package tickguard.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.InstantSource
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

/**
 * Toss keeps exactly one token valid per client: issuing a second one revokes
 * the first, and whoever still holds it starts getting `401 token-revoked`.
 *
 * So a plain "issue when the cache is empty" cache is not enough. Two callers
 * arriving together would issue twice and the first would be killed by the
 * second. Everything here exists to make concurrent callers share one issue.
 *
 * Single-flight is per instance, which is enough while there is one process.
 * A second process needs a shared store *and* a distributed lock — a shared
 * store alone just moves the race. That is deliberately not built until there
 * is a second process to justify its shape.
 *
 * The issue runs in [scope], not in the caller's coroutine: callers waiting on
 * a shared attempt must not all fail because the first of them was cancelled,
 * and a fresh issue after that would revoke the token the rest are about to
 * receive. [scope] must therefore be supervised.
 */
class TokenManager(
    private val issuer: TokenIssuer,
    private val clock: InstantSource,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private var cached: IssuedToken? = null
    private var inFlight: Deferred<IssuedToken>? = null

    suspend fun token(): String {
        val attempt =
            mutex.withLock {
                cached?.let { if (isUsable(it)) return it.accessToken }
                inFlight ?: issueShared().also { inFlight = it }
            }
        return attempt.await().accessToken
    }

    /**
     * Drops the cached token, but only if it is still [stale].
     *
     * The clock is not the only thing that can invalidate a token: issuing one
     * elsewhere revokes this one, and the only evidence is a 401 arriving while
     * our own expiry still looks fine. Without this, a reconnect loop would keep
     * presenting the same dead token forever.
     *
     * The comparison is the difference from the original. A request sent with
     * the previous token can come back 401 after a fresh one was issued; dropping
     * the fresh one would issue a third, which revokes the second, which every
     * caller now holds.
     */
    suspend fun invalidate(stale: String) {
        mutex.withLock { if (cached?.accessToken == stale) cached = null }
    }

    private fun isUsable(token: IssuedToken): Boolean = clock.instant() < token.expiresAt.minus(REFRESH_SKEW)

    // A failed issue must not be cached: the next caller has to be free to
    // retry. Clearing inFlight whatever the outcome keeps that true while still
    // collapsing everyone who arrived during this attempt into one request.
    private fun issueShared(): Deferred<IssuedToken> =
        scope.async {
            try {
                issuer.issue().also { issued -> mutex.withLock { cached = issued } }
            } finally {
                withContext(NonCancellable) { mutex.withLock { inFlight = null } }
            }
        }

    private companion object {
        /** Refresh this far ahead of expiry so a request in flight cannot outlive the token. */
        val REFRESH_SKEW = 1.minutes.toJavaDuration()
    }
}
