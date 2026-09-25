package tickguard.trading

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tickguard.testing.decimal
import java.time.LocalDate

class StrategyTest {
    @Test
    fun `benchmark asks for equal parts once and never again`() {
        val bars = mapOf("A" to series("A", "1" to "1"), "B" to series("B", "1" to "1"), "C" to series("C", "1" to "1"))
        val history = History(LocalDate.parse("2026-01-05"), bars, mapOf("A" to 1, "B" to 1, "C" to 1))
        val benchmark = BuyAndHold()

        val first = benchmark.targets(history)

        assertThat(first.keys).containsExactly("A", "B", "C")
        assertThat(
            first.values.fold(decimal("0")) { a, b -> a + b }.toDouble(),
        ).isCloseTo(
            1.0,
            org.assertj.core.api.Assertions
                .within(1e-15),
        )
        assertThat(benchmark.targets(history)).isEmpty()
    }
}
