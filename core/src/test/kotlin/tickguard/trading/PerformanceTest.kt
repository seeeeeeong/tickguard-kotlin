package tickguard.trading

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import tickguard.testing.decimal
import java.time.LocalDate

class PerformanceTest {
    private fun curve(vararg values: String) =
        values.mapIndexed { i, v -> DayValue(LocalDate.parse("2026-01-01").plusDays(i.toLong()), decimal(v)) }

    @Test
    fun `measures the deepest fall from a previous high, not from the start`() {
        val result = performance(curve("100", "120", "90", "130", "117"))

        assertThat(result.maxDrawdown).isCloseTo(0.25, within(1e-12))
        assertThat(result.totalReturn).isCloseTo(0.17, within(1e-12))
    }

    @Test
    fun `annualises growth over the calendar span covered`() {
        val year =
            listOf(
                DayValue(LocalDate.parse("2025-01-01"), decimal("100")),
                DayValue(LocalDate.parse("2027-01-01"), decimal("121")),
            )

        // Two years (731 days) to +21% is about 10% a year.
        assertThat(performance(year).cagr).isCloseTo(0.0999, within(1e-3))
    }

    @Test
    fun `gives a flat line no Sharpe rather than dividing by zero`() {
        assertThat(performance(curve("100", "100", "100")).sharpe).isEqualTo(0.0)
        assertThat(performance(curve("100")).days).isEqualTo(1)
    }
}
