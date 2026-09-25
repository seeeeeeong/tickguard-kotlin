package tickguard.execution

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tickguard.sla.Market
import tickguard.sla.MarketHours
import tickguard.sla.Session
import java.time.Instant

class OrderWindowTest {
    /** 2 November 2026, after US daylight saving ends: 23:30 to 06:00 in Seoul. */
    private val hours =
        MarketHours(
            Market.US,
            "2026-11-02",
            listOf(
                Session(
                    "2026-11-02 preMarket",
                    Instant.parse("2026-11-02T09:00:00Z"),
                    Instant.parse("2026-11-02T14:30:00Z"),
                ),
                Session(
                    "2026-11-02 regularMarket",
                    Instant.parse("2026-11-02T14:30:00Z"),
                    Instant.parse("2026-11-02T21:00:00Z"),
                ),
            ),
        )

    @Test
    fun `opens ten minutes after the regular open and closes an hour before the close`() {
        assertThat(inOrderWindow(hours, Instant.parse("2026-11-02T14:39:59Z"))).isFalse()
        assertThat(inOrderWindow(hours, Instant.parse("2026-11-02T14:40:00Z"))).isTrue()
        assertThat(inOrderWindow(hours, Instant.parse("2026-11-02T20:00:00Z"))).isTrue()
        assertThat(inOrderWindow(hours, Instant.parse("2026-11-02T20:00:01Z"))).isFalse()
    }

    @Test
    fun `is shut in the pre-market and without a calendar`() {
        assertThat(inOrderWindow(hours, Instant.parse("2026-11-02T12:00:00Z"))).isFalse()
        assertThat(inOrderWindow(null, Instant.parse("2026-11-02T15:00:00Z"))).isFalse()
    }

    @Test
    fun `says when the window a moment falls in closes`() {
        assertThat(orderWindowEnd(hours, Instant.parse("2026-11-02T15:00:00Z")))
            .isEqualTo(Instant.parse("2026-11-02T20:00:00Z"))
        assertThat(orderWindowEnd(hours, Instant.parse("2026-11-02T20:00:01Z"))).isNull()
        assertThat(orderWindowEnd(null, Instant.parse("2026-11-02T15:00:00Z"))).isNull()
    }

    @Test
    fun `gives the day's window for a page to count down to`() {
        assertThat(orderWindow(hours))
            .isEqualTo(Instant.parse("2026-11-02T14:40:00Z")..Instant.parse("2026-11-02T20:00:00Z"))
        assertThat(orderWindow(null)).isNull()
    }
}
