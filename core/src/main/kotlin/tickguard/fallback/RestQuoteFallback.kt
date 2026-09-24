package tickguard.fallback

import kotlinx.coroutines.CancellationException
import tickguard.sla.Market
import tickguard.stream.Decimal
import tickguard.stream.Trade
import java.time.Instant
import java.time.InstantSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class FallbackSymbol(
    val code: String,
    val type: String,
    val market: Market,
)

data class FallbackStats(
    val active: Boolean,
    val polls: Int,
    val quotes: Int,
    val failures: Int,
)

/** Longer than any gap in a healthy stream during a session; far shorter than an outage. */
val DEFAULT_SILENCE = 30.seconds

/**
 * Keeps the rules fed over REST while the stream is silent.
 *
 * The socket is the only source of prices, so when it drops — or stays open
 * and delivers nothing — every price alert goes dark with it. hummingbot
 * answers the same gap in its order tracking by polling REST often while
 * the user stream is quiet and rarely while it is healthy; this does that
 * for quotes. Silence is measured from the last quote on the stream, which
 * catches a closed socket and a silent open one alike.
 *
 * Only symbols whose market is open are asked about, so a closed market is
 * quiet rather than "degraded". Paused while the source IP is refused: REST
 * shares the allow list, and polling into a 403 every ten seconds only
 * fills the log.
 *
 * A REST price is not a trade. It carries no time and no size, so it goes to
 * the rules and nowhere else: not into recorded ticks, which a backtest
 * replays as trades, and not into the SLA watcher, which judges the stream.
 */
class RestQuoteFallback(
    private val fetchPrices: suspend (List<String>) -> Map<String, Decimal>,
    private val symbols: () -> List<FallbackSymbol>,
    private val isMarketOpen: (Market, Instant) -> Boolean,
    /** Time since the stream last delivered a quote, or since it could have. */
    private val streamSilentFor: () -> Duration,
    /** True while polling would only meet the same refusal. */
    private val paused: () -> Boolean,
    private val onQuote: (Trade) -> Unit,
    private val onError: (Exception) -> Unit = {},
    /** Silence tolerated before REST steps in. */
    private val silence: Duration = DEFAULT_SILENCE,
    private val clock: InstantSource = InstantSource.system(),
) {
    private var active = false
    private var polls = 0
    private var quotes = 0
    private var failures = 0

    /** Called on a timer. Does nothing while the stream is healthy. */
    @Suppress("TooGenericExceptionCaught") // Any failed poll is counted and retried on the next tick.
    suspend fun run() {
        val at = clock.instant()
        val open = symbols().filter { isMarketOpen(it.market, at) }
        active = !paused() && open.isNotEmpty() && streamSilentFor() >= silence
        if (!active) return

        polls += 1
        try {
            emit(open, fetchPrices(open.map { it.code }))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failures += 1
            onError(failure)
        }
    }

    fun stats() = FallbackStats(active, polls, quotes, failures)

    private fun emit(
        open: List<FallbackSymbol>,
        prices: Map<String, Decimal>,
    ) {
        for (symbol in open) {
            val price = prices[symbol.code] ?: continue
            quotes += 1
            onQuote(
                Trade(
                    type = symbol.type,
                    code = symbol.code,
                    price = price,
                    volume = Decimal.ZERO,
                    at = clock.instant(),
                    currency = if (symbol.market == Market.KR) "KRW" else "USD",
                ),
            )
        }
    }
}
