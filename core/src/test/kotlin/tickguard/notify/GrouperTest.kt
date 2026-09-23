package tickguard.notify

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tickguard.rules.Signal
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class GrouperTest {
    private fun signal(
        code: String,
        ruleId: String = "drawdown-7pct",
    ) = Signal(ruleId, code, "$code 평단 대비 -7.2%", "현재 100 · 평단 108", Instant.EPOCH)

    private class Harness(
        private val scope: TestScope,
        wait: Duration = 30.seconds,
    ) {
        val groups = mutableListOf<SignalGroup>()
        val grouper = Grouper(scope.backgroundScope, { groups += it }, wait)

        fun elapse(by: Duration = 30.seconds) {
            scope.advanceTimeBy(by)
            scope.runCurrent()
        }
    }

    @Test
    fun `sends symbols that cross together as one message`() =
        runTest {
            val h = Harness(this)

            listOf("AAPL", "TSLA", "NVDA").forEach { h.grouper.add(signal(it)) }
            h.elapse(29.seconds)
            assertThat(h.groups).isEmpty()

            h.elapse(1.seconds)

            assertThat(
                h.groups
                    .single()
                    .signals
                    .map { it.code },
            ).containsExactly("AAPL", "TSLA", "NVDA")
        }

    @Test
    fun `does not restart the wait when a signal joins`() =
        runTest {
            // Extending on every arrival is a debounce, and a debounce during a
            // sustained move never fires at all.
            val h = Harness(this)

            h.grouper.add(signal("AAPL"))
            h.elapse(20.seconds)
            h.grouper.add(signal("TSLA"))
            h.elapse(10.seconds)

            assertThat(h.groups.single().signals).hasSize(2)
        }

    @Test
    fun `keeps different rules in different groups`() =
        runTest {
            val h = Harness(this)

            h.grouper.add(signal("AAPL", "drawdown-7pct"))
            h.grouper.add(signal("AAPL", "rapid-move-3pct"))
            h.elapse()

            assertThat(h.groups.map { it.key }).containsExactlyInAnyOrder("drawdown-7pct", "rapid-move-3pct")
        }

    @Test
    fun `opens a new group after the previous one went out`() =
        runTest {
            val h = Harness(this)

            h.grouper.add(signal("AAPL"))
            h.elapse()
            h.grouper.add(signal("TSLA"))
            h.elapse()

            assertThat(h.groups).hasSize(2)
        }

    @Test
    fun `flushes what is waiting, so shutdown does not lose a group`() =
        runTest {
            val h = Harness(this)

            h.grouper.add(signal("AAPL"))
            assertThat(h.grouper.pending()).isEqualTo(1)

            h.grouper.flush()
            h.elapse()

            assertThat(h.groups).hasSize(1)
            assertThat(h.grouper.pending()).isZero()
        }

    @Test
    fun `sends immediately when grouping is turned off`() =
        runTest {
            val h = Harness(this, wait = Duration.ZERO)

            h.grouper.add(signal("AAPL"))

            assertThat(h.groups).hasSize(1)
        }
}
