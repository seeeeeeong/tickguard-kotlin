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

    @Test
    fun `flags a held symbol whose shares in the account differ from the ledger, but not the user's own`() {
        fun d(text: String) = Decimal.parse(text, "test")
        val account = mapOf("AAPL" to d("0.6"), "SPY" to d("1.2"), "AMZN" to d("3"))
        val ledger = mapOf("AAPL" to d("0.3"), "SPY" to d("1.2"), "AMZN" to d("1"))

        // AAPL doubled: a split the ledger predates, or an order no sleeve recorded.
        assertThat(mismatched(account, ledger, listOf("AAPL", "SPY", "AMZN"), setOf("AMZN"))).containsExactly("AAPL")
    }

    @Test
    fun `raises the run's buy limit to the dip sleeve's capital only while it is on`() {
        val dip = limitsFor(mapOf("D" to tickguard.trading.SleeveMode.DRY_RUN)).maxBuys
        val test =
            limitsFor(
                mapOf("A" to tickguard.trading.SleeveMode.LIVE, "D" to tickguard.trading.SleeveMode.OFF),
            ).maxBuys

        assertThat(dip).isEqualTo(TestSleeves.DIP_CAPITAL)
        assertThat(test.format(2)).isEqualTo("219.20")
    }

    @Test
    fun `names the replaced sleeves that still hold shares`() {
        val filled =
            tickguard.testing.order("a1", status = "FILLED", symbol = "SPY", price = null)
        assertThat(leftovers(TestSleeves.ALL, mapOf("A" to listOf(filled)))).containsExactly("A")
        assertThat(leftovers(TestSleeves.ALL, mapOf("D" to listOf(filled)))).isEmpty()
    }
}
