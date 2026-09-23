package tickguard.pipeline

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tickguard.testing.VirtualClock
import kotlin.time.Duration.Companion.milliseconds

class PumpTest {
    private val quote = "trade:us:AAPL"

    private fun <T> TestScope.pump(
        inbox: Inbox<T>,
        batchSize: Int = 256,
        onHandlerError: (Exception, T) -> Unit = { _, _ -> },
        handle: (T) -> Unit,
    ) = Pump(inbox, handle, VirtualClock(testScheduler), batchSize, onHandlerError).also { it.start(backgroundScope) }

    @Test
    fun `yields between batches, so other engine work runs during a burst`() =
        runTest {
            val inbox = Inbox<Int>()
            val handled = mutableListOf<Int>()
            pump(inbox, batchSize = 10) { handled += it }
            val seenByOtherWork = mutableListOf<Int>()

            repeat(25) { inbox.offer(quote, it) }
            backgroundScope.launch { seenByOtherWork += handled.size }
            runCurrent()

            // The other task ran after the first batch, not after the whole burst.
            assertThat(seenByOtherWork).containsExactly(10)
            assertThat(handled).hasSize(25)
        }

    @Test
    fun `handles each offered item once, however many offers woke it`() =
        runTest {
            val inbox = Inbox<Int>()
            val handled = mutableListOf<Int>()
            pump(inbox) { handled += it }

            repeat(3) { inbox.offer(quote, it) }
            runCurrent()
            inbox.offer(quote, 3)
            runCurrent()

            assertThat(handled).containsExactly(0, 1, 2, 3)
        }

    @Test
    fun `keeps going after a handler throws`() =
        runTest {
            val inbox = Inbox<Int>()
            val handled = mutableListOf<Int>()
            val errors = mutableListOf<Int>()
            val pump =
                pump(inbox, onHandlerError = { _, item -> errors += item }) {
                    check(it != 2) { "boom" }
                    handled += it
                }

            listOf(1, 2, 3).forEach { inbox.offer(quote, it) }
            runCurrent()

            assertThat(handled).containsExactly(1, 3)
            assertThat(errors).containsExactly(2)
            assertThat(pump.handled()).isEqualTo(2)
        }

    @Test
    fun `stops draining once stopped`() =
        runTest {
            val inbox = Inbox<Int>()
            val pump = pump(inbox) {}

            pump.stop()
            inbox.offer(quote, 1)
            runCurrent()

            assertThat(pump.handled()).isZero()
        }

    @Test
    fun `drains a real burst without dropping order events`() =
        runTest {
            val inbox = Inbox<String>(quoteCapacity = 500)
            val handled = mutableListOf<String>()
            pump(inbox) { handled += it }

            repeat(5_000) { inbox.offer(quote, "q$it") }
            repeat(20) { inbox.offer("personal:order:3", "o$it") }
            runCurrent()

            // Quotes overflowed and were trimmed; every order survived.
            assertThat(handled.filter { it.startsWith("o") }).hasSize(20)
            assertThat(inbox.stats().droppedQuotes).isEqualTo(4_500)
        }

    @Test
    fun `measures how long an item waited between the socket and its handler`() =
        runTest {
            val inbox = Inbox<Int>(clock = VirtualClock(testScheduler))
            inbox.offer(quote, 1)
            // The drain gets its turn 120ms after the item arrived.
            advanceTimeBy(120.milliseconds)

            val pump = pump(inbox) {}
            runCurrent()

            assertThat(pump.maxWait()).isEqualTo(120.milliseconds)
            assertThat(pump.handled()).isEqualTo(1)
        }
}
