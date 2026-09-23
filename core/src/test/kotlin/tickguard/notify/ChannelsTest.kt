package tickguard.notify

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tickguard.rules.Signal
import java.time.Instant

class ChannelsTest {
    private fun signal(code: String) =
        Signal("drawdown-7pct", code, "$code 평단 대비 -7.2%", "현재 100 · 평단 108", Instant.EPOCH)

    @Test
    fun `leads with the time and the title, since that is what a phone shows`() {
        val signal =
            Signal(
                "drawdown-7pct",
                "005930",
                "005930 평단 대비 -7.2%",
                "현재 63480 · 평단 68400",
                Instant.parse("2026-09-23T00:14:00Z"),
            )

        assertThat(
            formatSignal(signal),
        ).isEqualTo("[09:14:00] 005930 평단 대비 -7.2%\n현재 63480 · 평단 68400\n· drawdown-7pct")
    }

    @Test
    fun `reads as a single alert when only one fired`() {
        val text = formatGroup(SignalGroup("drawdown-7pct", listOf(signal("AAPL"))))

        assertThat(text).doesNotContain("건").contains("AAPL 평단 대비 -7.2%")
    }

    @Test
    fun `leads with the count, which is what a phone shows unopened`() {
        val text = formatGroup(SignalGroup("drawdown-7pct", listOf("AAPL", "TSLA", "NVDA").map(::signal)))

        assertThat(text).isEqualTo(
            "[09:00:00] drawdown-7pct — 3건\n" +
                "· AAPL 평단 대비 -7.2%\n  현재 100 · 평단 108\n" +
                "· TSLA 평단 대비 -7.2%\n  현재 100 · 평단 108\n" +
                "· NVDA 평단 대비 -7.2%\n  현재 100 · 평단 108",
        )
    }

    @Test
    fun `the console channel writes the text it was given`() =
        runTest {
            val lines = mutableListOf<String>()

            ConsoleChannel { lines += it }.send(Notification("k", "hello"))

            assertThat(lines).containsExactly("hello")
        }
}
