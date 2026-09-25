package tickguard.runner

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class StatusTest {
    private val now = Instant.parse("2026-09-25T03:00:00Z")

    @Test
    fun `shows how long the current socket has been open, not how long the process has run`() {
        val panel = streamPanel(connectedSince = now.minusSeconds(13 * 60), blockedSince = null, now = now)

        assertThat(panel.value).isEqualTo("connected 13m")
        assertThat(panel.ok).isTrue()
    }

    @Test
    fun `says the stream is down while Toss refuses the address`() {
        val panel = streamPanel(connectedSince = null, blockedSince = now.minusSeconds(60), now = now)

        assertThat(panel.value).isEqualTo("down · IP blocked")
        assertThat(panel.ok).isFalse()
    }

    @Test
    fun `says the stream is down between a close and the next open`() {
        assertThat(streamPanel(connectedSince = null, blockedSince = null, now = now).value)
            .isEqualTo("down · reconnecting")
    }

    @Test
    fun `keeps ages coarse enough to read at a glance`() {
        assertThat(age(now.minusSeconds(42), now)).isEqualTo("42s")
        assertThat(age(now.minusSeconds(5 * 3_600 + 59 * 60), now)).isEqualTo("5h")
        assertThat(age(now.minusSeconds(51 * 3_600), now)).isEqualTo("2d 3h")
    }
}
