package tickguard.fallback

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tickguard.sla.Market
import tickguard.stream.Decimal
import tickguard.stream.Trade
import tickguard.testing.decimal
import java.io.IOException
import java.time.Instant
import java.time.InstantSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val AMZN = FallbackSymbol("AMZN", "trade:us", Market.US)
private val SAMSUNG = FallbackSymbol("005930", "trade:kr", Market.KR)

class RestQuoteFallbackTest {
    private val quotes = mutableListOf<Trade>()
    private val asked = mutableListOf<List<String>>()

    private fun fallback(
        silentFor: Duration = 45.seconds,
        paused: Boolean = false,
        open: Set<Market> = setOf(Market.US),
    ) = RestQuoteFallback(
        fetchPrices = { codes ->
            asked += codes
            codes.associateWith { decimal("254.10") }
        },
        symbols = { listOf(AMZN, SAMSUNG) },
        isMarketOpen = { market, _ -> market in open },
        streamSilentFor = { silentFor },
        paused = { paused },
        onQuote = { quotes += it },
        clock = InstantSource.fixed(Instant.ofEpochMilli(1_000)),
    )

    @Test
    fun `stays idle while the stream is healthy`() =
        runTest {
            val fallback = fallback(silentFor = 5.seconds)

            fallback.run()

            assertThat(asked).isEmpty()
            assertThat(fallback.stats().active).isFalse()
        }

    @Test
    fun `feeds REST prices to the rules once the stream has gone quiet`() =
        runTest {
            val fallback = fallback()

            fallback.run()

            val quote = quotes.single()
            assertThat(quote.code).isEqualTo("AMZN")
            assertThat(quote.type).isEqualTo("trade:us")
            assertThat(quote.currency).isEqualTo("USD")
            assertThat(quote.price.toPlainString()).isEqualTo("254.1")
            assertThat(quote.volume.toPlainString()).isEqualTo("0")
            assertThat(fallback.stats()).isEqualTo(FallbackStats(active = true, polls = 1, quotes = 1, failures = 0))
        }

    @Test
    fun `asks only about markets that are open`() =
        runTest {
            // A closed market is quiet, not degraded.
            fallback().run()

            assertThat(asked).containsExactly(listOf("AMZN"))
        }

    @Test
    fun `does not poll at all when every market is closed`() =
        runTest {
            fallback(open = emptySet()).run()

            assertThat(asked).isEmpty()
        }

    @Test
    fun `pauses while the IP is refused, since REST shares the allow list`() =
        runTest {
            fallback(paused = true).run()

            assertThat(asked).isEmpty()
        }

    @Test
    fun `counts a failed poll and tries again next time`() =
        runTest {
            val errors = mutableListOf<Exception>()
            var fail = true
            val fallback =
                RestQuoteFallback(
                    fetchPrices = { codes ->
                        if (fail) throw IOException("fetch failed (ECONNRESET)")
                        codes.associateWith { Decimal.ONE }
                    },
                    symbols = { listOf(AMZN) },
                    isMarketOpen = { _, _ -> true },
                    streamSilentFor = { 60.seconds },
                    paused = { false },
                    onQuote = {},
                    onError = { errors += it },
                )

            fallback.run()
            fail = false
            fallback.run()

            assertThat(errors).hasSize(1)
            assertThat(fallback.stats()).isEqualTo(FallbackStats(active = true, polls = 2, quotes = 1, failures = 1))
        }
}
