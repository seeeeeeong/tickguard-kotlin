package tickguard.trading

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tickguard.rest.RateLimitGroup
import tickguard.testing.StubRest
import tickguard.testing.decimal
import java.time.LocalDate

class CandlesTest {
    private fun candle(day: String) =
        """{"timestamp":"${day}T00:00:00+09:00","openPrice":"71600","highPrice":"72300","lowPrice":"71500",""" +
            """"closePrice":"72000","volume":"3521000","currency":"KRW"}"""

    private fun page(
        vararg days: String,
        next: String?,
    ) = """{"result":{"candles":[${days.joinToString(",") { candle(it) }}],""" +
        """"nextBefore":${next?.let { "\"${it}T00:00:00+09:00\"" } ?: "null"}}}"""

    @Test
    fun `reads the documented daily candle, dated in its own offset`() {
        val bars = parseCandles("005930", Json.parseToJsonElement(page("2026-03-25", next = null))).bars

        assertThat(bars.single()).isEqualTo(
            Bar(
                "005930",
                LocalDate.parse("2026-03-25"),
                decimal("71600"),
                decimal("72300"),
                decimal("71500"),
                decimal("72000"),
                decimal("3521000"),
            ),
        )
    }

    @Test
    fun `pages back to the start day, keeping the day two pages share once`() =
        runTest {
            // nextBefore is inclusive: the second page starts on the day the first ended.
            val rest =
                StubRest(
                    page("2026-03-25", "2026-03-24", next = "2026-03-24"),
                    page("2026-03-24", "2026-03-23", "2026-03-20", next = "2026-03-20"),
                    page("2026-03-20", "2026-03-19", next = null),
                )

            val fetched = fetchDailyBars(rest, "005930", LocalDate.parse("2026-03-20"))

            assertThat(
                fetched.bars.map {
                    it.day.toString()
                },
            ).containsExactly("2026-03-20", "2026-03-23", "2026-03-24", "2026-03-25")
            // Stopped once a page reached the start day; the third was never asked for.
            assertThat(rest.asked).hasSize(2)
            assertThat(rest.asked.map { it.query["before"] }).containsExactly(null, "2026-03-24T00:00:00+09:00")
            assertThat(
                rest.asked.all { it.group == RateLimitGroup.MARKET_DATA_CHART && it.query["interval"] == "1d" },
            ).isTrue()
        }

    @Test
    fun `stops at the last page and reports a candle it cannot read`() =
        runTest {
            val broken = candle("2026-03-24").replace(""""closePrice":"72000"""", """"closePrice":null""")
            val rest = StubRest("""{"result":{"candles":[${candle("2026-03-25")},$broken],"nextBefore":null}}""")

            val fetched = fetchDailyBars(rest, "005930", LocalDate.parse("2020-01-01"))

            assertThat(fetched.bars).hasSize(1)
            assertThat(fetched.unreadable).containsExactly("005930 2026-03-24T00:00:00+09:00: closePrice is missing")
        }

    @Test
    fun `refreshes each symbol's recent bars into the store, replacing what it fetches again`() =
        runTest {
            val stored = LinkedHashMap<Pair<String, LocalDate>, Bar>()
            val store =
                object : BarStore {
                    override suspend fun recordBars(bars: List<Bar>) {
                        bars.forEach { stored[it.code to it.day] = it }
                    }

                    override suspend fun bars(
                        code: String,
                        from: LocalDate,
                        to: LocalDate,
                    ) = stored.values.filter { it.code == code && it.day in from..to }
                }
            val rest = StubRest(page("2026-03-25", "2026-03-24", next = null))

            val refreshed = refreshBars(rest, store, listOf("A", "B"), LocalDate.parse("2026-03-20"))
            refreshBars(rest, store, listOf("A"), LocalDate.parse("2026-03-20"))

            val days = setOf(LocalDate.parse("2026-03-25"), LocalDate.parse("2026-03-24"))
            assertThat(refreshed).isEqualTo(
                RefreshedBars(
                    written = 4,
                    unreadable = emptyList(),
                    fetched =
                        mapOf("A" to days, "B" to days),
                ),
            )
            assertThat(
                stored.keys.map {
                    "${it.first} ${it.second}"
                },
            ).containsExactly("A 2026-03-24", "A 2026-03-25", "B 2026-03-24", "B 2026-03-25")
            assertThat(rest.asked.map { it.query["symbol"] }).containsExactly("A", "B", "A")
        }
}
