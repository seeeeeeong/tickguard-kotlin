package tickguard.trading

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tickguard.testing.decimal
import java.time.LocalDate

class BarsTest {
    private val bars = series("A", "1" to "1", "2" to "2", "3" to "3")
    private val day2 = LocalDate.parse("2026-01-06")

    @Test
    fun `shows the bars up to the decision day and none after`() {
        val history = History(day2, mapOf("A" to bars), mapOf("A" to 2))

        assertThat(history.bars("A", 10).map { it.close }).containsExactly(decimal("1"), decimal("2"))
        assertThat(history.closes("A", 2)).containsExactly(decimal("1"), decimal("2"))
        assertThat(history.closes("A", 3)).isNull()
        assertThat(history.tradedToday("A")).isTrue()
    }

    @Test
    fun `knows when a symbol did not trade on the decision day`() {
        val history = History(day2.plusDays(1), mapOf("A" to bars), mapOf("A" to 2))

        assertThat(history.tradedToday("A")).isFalse()
        assertThat(history.bars("B", 5)).isEmpty()
    }
}
