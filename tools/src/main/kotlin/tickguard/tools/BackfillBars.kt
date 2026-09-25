package tickguard.tools

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import tickguard.auth.TokenManager
import tickguard.auth.loadCredentials
import tickguard.toss.auth.TossAuthClient
import tickguard.toss.rest.OkHttpRestClient
import tickguard.trading.fetchDailyBars
import java.time.InstantSource
import java.time.LocalDate
import kotlin.system.exitProcess

/**
 * Fetches daily bars into the service's store, for backtests.
 *
 *   ./gradlew :tools:backfillBars --args="AAPL,GOOGL 2015-01-01"
 *
 * Writes to Postgres when TICKGUARD_PG_URL is set, otherwise to the SQLite
 * file in TICKGUARD_DB. It issues its own Toss token, and only one token is
 * valid per client: stop the running service first, or each revokes the other.
 * Re-running is safe; a day already stored is replaced by the fresh fetch.
 */
@Suppress("InjectDispatcher") // An operator entry point is its own composition root.
suspend fun main(args: Array<String>) {
    val codes =
        args
            .getOrNull(0)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
    if (codes.isNullOrEmpty()) {
        System.err.println("usage: backfillBars SYMBOL[,SYMBOL…] [FROM, default $DEFAULT_FROM]")
        exitProcess(2)
    }
    val from = LocalDate.parse(args.getOrNull(1) ?: DEFAULT_FROM)
    val env = environment()
    val clock = InstantSource.system()
    val http = OkHttpClient()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val tokens = TokenManager(TossAuthClient(loadCredentials(env), clock, http), clock, scope)
    val rest = OkHttpRestClient(http, tokens::token, tokens::invalidate)
    val store = openStore(env)
    try {
        for (code in codes) {
            val fetched = fetchDailyBars(rest, code, from)
            store.recordBars(fetched.bars)
            val span = fetched.bars.firstOrNull()?.let { "${it.day}..${fetched.bars.last().day}" } ?: "none"
            println("$code: ${fetched.bars.size} bars ($span), ${fetched.unreadable.size} unreadable")
            fetched.unreadable.forEach { System.err.println("  ✖ $it") }
        }
    } finally {
        withContext(NonCancellable) { store.close() }
        scope.cancel()
    }
}

/** Far enough back for several market regimes; the API returns what it has. */
private const val DEFAULT_FROM = "2015-01-01"
