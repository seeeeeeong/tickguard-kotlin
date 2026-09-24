package tickguard.tools

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class BacktestTest {
    @Test
    fun `reads durations the way the original's flags took them`() {
        assertThat(BacktestOptions.duration("30s")).isEqualTo(30.seconds)
        assertThat(BacktestOptions.duration("5m")).isEqualTo(5.minutes)
        assertThat(BacktestOptions.duration("1h")).isEqualTo(1.hours)
        assertThatThrownBy {
            BacktestOptions.duration(
                "5 min",
            )
        }.hasMessage("duration must look like 30s, 5m or 1h; got 5 min")
    }

    @Test
    fun `names each threshold's case as the original did`() {
        val options = BacktestOptions.parse(listOf("--thresholds", "0.5,1", "--window", "30s"))

        assertThat(options.cases.map { it.name }).containsExactly("rapid-move 0.5% / 0.5m", "rapid-move 1% / 0.5m")
    }

    @Test
    fun `refuses a drawdown replay without averages, which are not recorded`() {
        assertThatThrownBy {
            BacktestOptions.parse(listOf("--rule", "drawdown"))
        }.hasMessageStartingWith("drawdown needs --average")
    }
}
