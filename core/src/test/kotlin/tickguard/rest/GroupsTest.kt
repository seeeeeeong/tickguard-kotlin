package tickguard.rest

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class GroupsTest {
    @Test
    fun `cuts the limit during the opening auction`() {
        // 09:05 KST is 00:05 UTC.
        assertThat(limitAt(RateLimitGroup.ORDER_INFO, Instant.parse("2026-09-23T00:05:00Z"))).isEqualTo(3)
    }

    @Test
    fun `restores it outside the auction`() {
        assertThat(limitAt(RateLimitGroup.ORDER_INFO, Instant.parse("2026-09-23T00:10:00Z"))).isEqualTo(6)
        assertThat(limitAt(RateLimitGroup.ORDER_INFO, Instant.parse("2026-09-22T23:59:00Z"))).isEqualTo(6)
    }

    @Test
    fun `leaves groups without a peak rule alone`() {
        assertThat(limitAt(RateLimitGroup.MARKET_DATA, Instant.parse("2026-09-23T00:05:00Z"))).isEqualTo(15)
    }
}
