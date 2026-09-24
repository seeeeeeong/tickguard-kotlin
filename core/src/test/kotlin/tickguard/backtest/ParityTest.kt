package tickguard.backtest

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tickguard.store.TickRow
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds

/**
 * The Kotlin replay against the original's, over the same made-up ticks.
 * Both files were written by tools/parity/replay-golden.ts, running the
 * original's own code.
 */
class ParityTest {
    private fun resource(name: String) =
        checkNotNull(javaClass.getResourceAsStream("/parity/$name")) { "missing $name" }.bufferedReader().readText()

    private val ticks =
        resource("ticks.tsv").lines().filter { it.isNotEmpty() }.map { line ->
            val cells = line.split('\t')
            val at = { index: Int -> Instant.ofEpochMilli(cells[index].toLong()) }
            TickRow(cells[0], cells[1], cells[2], cells[3], cells[4], at(5), at(6))
        }

    private val golden = resource("fires.tsv")

    @Test
    fun `fires exactly where the original fired, and measures exactly what it measured`() {
        assertThat(parityLines(ticks)).isEqualTo(golden)
    }

    @Test
    fun `would notice a hold one millisecond longer, so the comparison can fail`() {
        val skewed = parityLines(ticks, parityCases(holdSkew = 1.milliseconds))

        assertThat(skewed).isNotEqualTo(golden)
    }
}
