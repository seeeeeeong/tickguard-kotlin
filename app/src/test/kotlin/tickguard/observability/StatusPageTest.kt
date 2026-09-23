package tickguard.observability

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class StatusPageTest {
    @Test
    fun `escapes panel text, which comes from symbols and error messages`() {
        val html = renderStatusPage(listOf(StatusPanel("<script>", "a & \"b\"")))

        assertThat(html).contains("&lt;script&gt;").contains("a &amp; &quot;b&quot;").doesNotContain("<script>")
    }

    @Test
    fun `marks a panel good or bad, and leaves an unset one neutral`() {
        val html =
            renderStatusPage(
                listOf(
                    StatusPanel("stream", "up", ok = true),
                    StatusPanel("drift", "high", ok = false),
                    StatusPanel("ticks", "100"),
                ),
            )

        assertThat(html).contains("class=\"value ok\"").contains("class=\"value bad\"").contains("class=\"value\"")
    }

    @Test
    fun `stamps the page as toISOString did, with milliseconds in UTC`() {
        val html = renderStatusPage(emptyList(), Instant.parse("2026-09-24T00:14:00Z"))

        assertThat(html).contains("<h1>tickguard · 2026-09-24T00:14:00.000Z</h1>")
    }
}
