package tickguard.time

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.TimeZone

class KstTest {
    private val originalZone = TimeZone.getDefault()

    @AfterEach
    fun restoreZone() = TimeZone.setDefault(originalZone)

    @Test
    fun `rolls at midnight in Seoul, not UTC`() {
        assertThat(kstDate(Instant.parse("2026-09-23T14:59:00Z"))).isEqualTo("2026-09-23")
        assertThat(kstDate(Instant.parse("2026-09-23T15:01:00Z"))).isEqualTo("2026-09-24")
    }

    @Test
    fun `stamps in Seoul time`() {
        // 09:14 in Seoul, just after the KRX open — the first live alert read 00:14.
        assertThat(kstTime(Instant.parse("2026-09-23T00:14:00Z"))).isEqualTo("09:14:00")
    }

    @Test
    fun `wraps past midnight along with the date`() {
        val at = Instant.parse("2026-09-23T15:01:00Z")

        assertThat(kstTime(at)).isEqualTo("00:01:00")
        assertThat(kstDate(at)).isEqualTo("2026-09-24")
    }

    @Test
    fun `ignores the host time zone, which a container sets to UTC`() {
        val at = Instant.parse("2026-09-23T00:14:00Z")

        for (zone in listOf("UTC", "America/New_York", "Asia/Seoul")) {
            TimeZone.setDefault(TimeZone.getTimeZone(zone))
            assertThat(kstTime(at)).isEqualTo("09:14:00")
        }
    }

    @Test
    fun `starts the day at midnight in Seoul, which is three in the afternoon UTC the day before`() {
        assertThat(kstDayStart(Instant.parse("2026-09-23T03:00:00Z"))).isEqualTo(Instant.parse("2026-09-22T15:00:00Z"))
        assertThat(kstDayStart(Instant.parse("2026-09-22T15:00:00Z"))).isEqualTo(Instant.parse("2026-09-22T15:00:00Z"))
    }
}
