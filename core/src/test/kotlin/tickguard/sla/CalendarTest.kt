package tickguard.sla

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tickguard.json.StrictJson
import tickguard.testing.StubRest
import java.time.Duration
import java.time.Instant

class CalendarTest {
    /** Trimmed from a live /api/v1/market-calendar/KR response. */
    private val kr =
        """
        {"result":{"today":{"date":"2026-09-23","integrated":{
          "preMarket":{"startTime":"2026-09-23T08:00:00.000+09:00","endTime":"2026-09-23T09:00:00.000+09:00"},
          "regularMarket":{"startTime":"2026-09-23T09:00:00.000+09:00","endTime":"2026-09-23T15:30:00.000+09:00"},
          "afterMarket":{"startTime":"2026-09-23T15:30:00.000+09:00","endTime":"2026-09-23T20:00:00.000+09:00"}}}}}
        """.trimIndent()

    /** US, where regular hours cross midnight in KST. */
    private val us =
        """
        {"result":{"today":{"date":"2026-09-23",
          "dayMarket":{"startTime":"2026-09-23T09:00:00.000+09:00","endTime":"2026-09-23T17:00:00.000+09:00"},
          "regularMarket":{"startTime":"2026-09-23T22:30:00.000+09:00","endTime":"2026-09-24T05:00:00.000+09:00"}}}}
        """.trimIndent()

    /** At 00:26 KST the live US session belongs to the previous business day. */
    private val usWithPrevious =
        """
        {"result":{
          "today":{"date":"2026-09-23",
            "regularMarket":{"startTime":"2026-09-23T22:30:00.000+09:00","endTime":"2026-09-24T05:00:00.000+09:00"}},
          "previousBusinessDay":{"date":"2026-09-22",
            "regularMarket":{"startTime":"2026-09-22T22:30:00.000+09:00","endTime":"2026-09-23T05:00:00.000+09:00"}}}}
        """.trimIndent()

    private fun parse(
        market: Market,
        body: String,
    ) = parseCalendar(market, StrictJson.parse(body))

    @Test
    fun `reads KR sessions from under the integrated wrapper`() {
        val hours = parse(Market.KR, kr)!!

        assertThat(hours.sessions.map { it.name })
            .containsExactly("2026-09-23 preMarket", "2026-09-23 regularMarket", "2026-09-23 afterMarket")
        assertThat(hours.date).isEqualTo("2026-09-23")
    }

    @Test
    fun `reads US sessions from the top level, including dayMarket`() {
        assertThat(parse(Market.US, us)!!.sessions.map { it.name })
            .containsExactly("2026-09-23 dayMarket", "2026-09-23 regularMarket")
    }

    @Test
    fun `keeps a session that crosses midnight as one instant range`() {
        // 22:30 KST to 05:00 the next day. Comparing times of day would split it.
        val regular = parse(Market.US, us)!!.sessions.single { it.name.endsWith("regularMarket") }

        assertThat(Duration.between(regular.from, regular.to)).isEqualTo(Duration.ofMinutes(390))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "\"nope\"",
            """{"result":{}}""",
            """{"result":{"today":{"regularMarket":{"startTime":"x"}}}}""",
            """{"result":{"today":{"regularMarket":""" +
                """{"startTime":"2026-09-23T10:00:00Z","endTime":"2026-09-23T09:00:00Z"}}}}""",
        ],
    )
    fun `returns null for a body with no readable session`(body: String) {
        assertThat(parse(Market.KR, body)).isNull()
    }

    @Test
    fun `includes the previous business day, whose session may still be running`() {
        assertThat(parse(Market.US, usWithPrevious)!!.sessions.map { it.name })
            .containsExactly("2026-09-22 regularMarket", "2026-09-23 regularMarket")
    }

    @Test
    fun `covers 00 26 KST, which reading only today reports as closed`() =
        runTest {
            val calendar = Calendar(StubRest(usWithPrevious))
            calendar.load(Market.US)

            assertThat(calendar.isOpen(Market.US, Instant.parse("2026-09-23T00:26:00.000+09:00"))).isTrue()
        }

    @Test
    fun `says closed while hours are unknown, rather than guessing`() {
        assertThat(Calendar(StubRest(kr)).isOpen(Market.KR, Instant.parse("2026-09-23T01:00:00Z"))).isFalse()
    }
}
