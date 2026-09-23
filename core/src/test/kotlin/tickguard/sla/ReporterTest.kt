package tickguard.sla

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tickguard.rules.Signal
import java.time.Instant
import java.time.InstantSource
import kotlin.time.Duration.Companion.minutes

class ReporterTest {
    private val sent = mutableListOf<Signal>()
    private var blocked = false
    private val reporter = IncidentReporter({ sent += it }, { blocked }, InstantSource { Instant.EPOCH })

    private fun incident(code: String) = Incident(code, Market.US, 12.minutes, Instant.EPOCH)

    @Test
    fun `pages a recovery for a silence it paged`() {
        reporter.incident(incident("AMZN"))
        reporter.recovered("AMZN", 14.minutes)

        assertThat(sent.map { it.title }).containsExactly("AMZN 시세가 12분째 없음", "AMZN 시세 복구 · 14분간 없었음")
        assertThat(sent.map { it.detail }).containsExactly("US 시장은 열려 있음", "시세가 다시 들어오고 있음")
    }

    @Test
    fun `stays quiet about a recovery nobody was told needed recovering`() {
        // Held back during an IP block; the unblock alert covers it.
        blocked = true
        reporter.incident(incident("AMZN"))
        blocked = false

        reporter.recovered("AMZN", 30.minutes)

        assertThat(sent).isEmpty()
        assertThat(reporter.stats()).isEqualTo(ReporterStats(reported = 0, inhibited = 1, recovered = 0))
    }

    @Test
    fun `pages each recovery once`() {
        reporter.incident(incident("AMZN"))

        reporter.recovered("AMZN", 1.minutes)
        reporter.recovered("AMZN", 1.minutes)

        assertThat(sent).hasSize(2)
    }
}
