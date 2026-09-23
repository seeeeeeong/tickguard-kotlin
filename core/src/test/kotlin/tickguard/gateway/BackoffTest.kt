package tickguard.gateway

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class BackoffTest {
    private val options = BackoffOptions(base = 1.seconds, cap = 30.seconds)

    @Test
    fun `never returns zero, so a flapping server cannot become a tight loop`() {
        repeat(10) { attempt -> assertThat(nextDelay(attempt, options) { 0.0 }).isGreaterThan(Duration.ZERO) }
    }

    @Test
    fun `spreads clients across a window rather than releasing them together`() {
        assertThat(nextDelay(0, options) { 0.0 }).isEqualTo(500.milliseconds)
        assertThat(nextDelay(0, options) { 1.0 }).isEqualTo(1.seconds)
    }

    @Test
    fun `grows exponentially`() {
        val atCeiling = { attempt: Int -> nextDelay(attempt, options) { 1.0 } }

        assertThat((0..3).map(atCeiling)).containsExactly(1.seconds, 2.seconds, 4.seconds, 8.seconds)
    }

    @Test
    fun `stops growing at the cap`() {
        assertThat(nextDelay(20, options) { 1.0 }).isEqualTo(options.cap)
        assertThat(nextDelay(200, options) { 1.0 }).isEqualTo(options.cap)
    }

    @Test
    fun `treats a negative attempt as the first one`() {
        assertThat(nextDelay(-5, options) { 1.0 }).isEqualTo(1.seconds)
    }

    @Test
    fun `caps the default at 30s`() {
        assertThat(nextDelay(99, DEFAULT_BACKOFF) { 1.0 }).isEqualTo(30.seconds)
    }
}
