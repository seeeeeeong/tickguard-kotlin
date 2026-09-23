package tickguard.pipeline

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.concurrent.thread

class InboxTest {
    private val quote = "trade:us:AAPL"
    private val order = "personal:order:3"

    private fun <T> Inbox<T>.takeItems(max: Int) = take(max).map { it.item }

    @Test
    fun `separates order events from quotes, because only one of them is replaceable`() {
        assertThat(laneOf(quote)).isEqualTo(Lane.QUOTES)
        assertThat(laneOf("orderbook:kr:005930")).isEqualTo(Lane.QUOTES)
        assertThat(laneOf(order)).isEqualTo(Lane.ORDERS)
    }

    @Test
    fun `treats an unparsable topic as a quote, the droppable side`() {
        assertThat(laneOf("garbage")).isEqualTo(Lane.QUOTES)
    }

    @Test
    fun `drops the oldest quotes on overflow, matching how the server already behaves`() {
        val overflows = mutableListOf<Pair<Lane, Int>>()
        val inbox = Inbox<Int>(quoteCapacity = 3, onOverflow = { lane, n -> overflows += lane to n })

        listOf(1, 2, 3, 4, 5).forEach { inbox.offer(quote, it) }

        assertThat(inbox.takeItems(10)).containsExactly(3, 4, 5)
        assertThat(inbox.stats().droppedQuotes).isEqualTo(2)
        assertThat(overflows).contains(Lane.QUOTES to 1)
    }

    @Test
    fun `never drops an order event`() {
        val inbox = Inbox<Int>(quoteCapacity = 2, orderCapacity = 100)

        repeat(50) {
            inbox.offer(order, it)
            inbox.offer(quote, -it)
        }

        assertThat(inbox.size(Lane.ORDERS)).isEqualTo(50)
        assertThat(inbox.stats().droppedQuotes).isEqualTo(48)
    }

    @Test
    fun `refuses an order rather than silently discarding one`() {
        val full = mutableListOf<Int>()
        val inbox = Inbox<Int>(orderCapacity = 2, onOrderBacklogFull = { full += it })

        assertThat(inbox.offer(order, 1)).isTrue()
        assertThat(inbox.offer(order, 2)).isTrue()
        assertThat(inbox.offer(order, 3)).isFalse()
        assertThat(full).containsExactly(2)
        assertThat(inbox.size(Lane.ORDERS)).isEqualTo(2)
    }

    @Test
    fun `takes orders first, since they are the ones with a deadline`() {
        val inbox = Inbox<String>()

        inbox.offer(quote, "q1")
        inbox.offer(order, "o1")
        inbox.offer(quote, "q2")

        assertThat(inbox.takeItems(10)).containsExactly("o1", "q1", "q2")
    }

    @Test
    fun `does not reach into quotes when orders already fill the batch`() {
        val inbox = Inbox<String>()

        inbox.offer(order, "o1")
        inbox.offer(order, "o2")
        inbox.offer(quote, "q1")

        assertThat(inbox.takeItems(2)).containsExactly("o1", "o2")
        assertThat(inbox.size()).isEqualTo(1)
    }

    @Test
    fun `remembers the high-water mark, which is what tells you the capacity is wrong`() {
        val inbox = Inbox<Int>(quoteCapacity = 100)

        repeat(40) { inbox.offer(quote, it) }
        inbox.take(40)
        inbox.offer(quote, 99)

        assertThat(inbox.stats().maxQueued[Lane.QUOTES]).isEqualTo(40)
        assertThat(inbox.stats().queued[Lane.QUOTES]).isEqualTo(1)
    }

    @Test
    fun `loses no order offered from several threads at once`() {
        // The reader thread offers while the engine takes. Across threads, every
        // order must arrive exactly once.
        val inbox = Inbox<String>(quoteCapacity = 10, orderCapacity = 100_000)
        val offering =
            List(4) { worker ->
                thread {
                    repeat(5_000) {
                        inbox.offer(order, "$worker-$it")
                        inbox.offer(quote, "q")
                    }
                }
            }
        val taken = mutableListOf<String>()
        while (offering.any { it.isAlive } || inbox.size() > 0) taken += inbox.takeItems(64)
        offering.forEach { it.join() }
        taken += inbox.takeItems(Int.MAX_VALUE)

        val orders = taken.filter { it != "q" }
        assertThat(orders).hasSize(20_000).doesNotHaveDuplicates()
    }
}
