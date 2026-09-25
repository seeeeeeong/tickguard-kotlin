package tickguard.execution

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tickguard.trading.SleeveMode
import java.time.Instant

class ArmingTest {
    private val now = Instant.parse("2026-09-25T14:00:00Z")
    private val configured = mapOf("A" to SleeveMode.DRY_RUN, "B" to SleeveMode.OFF, "C" to SleeveMode.LIVE)

    @Test
    fun `leaves configuration as it is until a person switches something`() {
        assertThat(Arming().modes(configured, now)).isEqualTo(configured)
        assertThat(Arming().enabled(configured = true)).isTrue()
    }

    @Test
    fun `runs a dry-run sleeve live until the window closes, and never an off one`() {
        val armed = Arming(liveUntil = now.plusSeconds(60))

        assertThat(armed.modes(configured, now))
            .containsEntry("A", SleeveMode.LIVE)
            .containsEntry("B", SleeveMode.OFF)
            .containsEntry("C", SleeveMode.LIVE)
        assertThat(armed.modes(configured, now.plusSeconds(60))).isEqualTo(configured)
    }

    @Test
    fun `stops everything, and cannot turn on a kill switch configuration left off`() {
        val stopped = Arming(stopped = true, liveUntil = now.plusSeconds(60))

        assertThat(stopped.enabled(configured = true)).isFalse()
        assertThat(stopped.isLive(now)).isFalse()
        assertThat(Arming(liveUntil = now.plusSeconds(60)).enabled(configured = false)).isFalse()
    }
}
