package tickguard.runner

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tickguard.stream.Decimal
import tickguard.trading.SleevePosition
import tickguard.trading.SleeveProposal
import tickguard.trading.TestSleeves
import java.time.Instant
import java.time.LocalDate

class SleeveDeskTest {
    private val monday = Instant.parse("2026-11-02T00:00:00Z")
    private val tuesday = Instant.parse("2026-11-03T00:00:00Z")

    private fun proposal(id: String) =
        SleeveProposal(
            TestSleeves.ALL.single { it.id == id },
            SleevePosition(emptyMap(), Decimal.ZERO, Decimal.ZERO, 0),
            Decimal.ZERO,
            emptyMap(),
            emptyList(),
            null,
            LocalDate.parse("2026-10-30"),
        )

    private val monthly = listOf(proposal("A"), proposal("D"))

    @Test
    fun `keeps a month's proposal that no session has placed when the next dip proposal comes`() {
        assertThat(
            waiting(monthly, monday, null, tuesday, listOf("D")).map { it.sleeve.id },
        ).containsExactly("A")
    }

    @Test
    fun `keeps nothing once placed, once too old, or when there was nothing`() {
        assertThat(waiting(monthly, monday, monday, tuesday, listOf("D"))).isEmpty()
        assertThat(waiting(monthly, monday, null, tuesday.plusSeconds(7 * 3600), listOf("D"))).isEmpty()
        assertThat(waiting(emptyList(), null, null, tuesday, listOf("D"))).isEmpty()
    }
}
