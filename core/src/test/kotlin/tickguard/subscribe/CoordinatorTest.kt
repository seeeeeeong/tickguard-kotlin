package tickguard.subscribe

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tickguard.gateway.RejectedTopic
import tickguard.gateway.ServerFrame
import tickguard.testing.VirtualClock
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class CoordinatorTest {
    private fun trade(code: String) = Topic("trade:us", code)

    private fun ack(
        id: String,
        subscribed: List<String>,
        rejected: List<String>,
    ) = ServerFrame.Subscriptions(
        id,
        subscribed,
        rejected.map {
            RejectedTopic(it, "stock-not-found", "해당 종목을 찾을 수 없습니다.")
        },
    )

    /** Runs scheduled declarations on demand and says how long each waited. */
    private class Harness(
        private val scope: TestScope,
        store: RejectionStore? = null,
    ) {
        val sent = mutableListOf<String>()
        val storeErrors = mutableListOf<Exception>()
        val coordinator =
            SubscriptionCoordinator(
                scope = scope.backgroundScope,
                clock = VirtualClock(scope.testScheduler),
                rejections = store,
                onStoreError = { storeErrors += it },
                coalesce = 50.milliseconds,
                minInterval = 250.milliseconds,
            )

        fun attach() = coordinator.attach { sent += it }

        /** Advances to the next declaration and returns how long it waited, or null if none is scheduled. */
        fun runPending(): Duration? {
            val before = sent.size
            var waited = 0L
            while (sent.size == before && waited < 2_000) {
                scope.advanceTimeBy(1)
                scope.runCurrent()
                waited += 1
            }
            return if (sent.size > before) waited.milliseconds else null
        }

        fun last() = Json.parseToJsonElement(sent.last())
    }

    private fun json(text: String) = Json.parseToJsonElement(text)

    @Test
    fun `coalesces a burst of changes into one declaration`() =
        runTest {
            val h = Harness(this)
            h.attach()

            h.coordinator.add(listOf(trade("AAPL")))
            h.coordinator.add(listOf(trade("TSLA")))
            h.coordinator.remove(listOf(trade("AAPL")))
            h.coordinator.add(listOf(trade("NVDA")))
            h.runPending()

            assertThat(h.sent).hasSize(1)
            assertThat(h.last()).isEqualTo(json("""[{"id":"g1-1"},{"type":"trade:us","codes":["NVDA","TSLA"]}]"""))
        }

    @Test
    fun `waits out the rate limit before declaring again`() =
        runTest {
            val h = Harness(this)
            h.attach()
            h.coordinator.add(listOf(trade("AAPL")))
            h.runPending()

            h.coordinator.add(listOf(trade("TSLA")))

            // 250ms since the last declaration, not the 50ms coalesce window.
            assertThat(h.runPending()).isEqualTo(250.milliseconds)
        }

    @Test
    fun `declares the whole set on every attach, since a new socket holds none`() =
        runTest {
            val h = Harness(this)
            h.attach()
            h.coordinator.add(listOf(trade("AAPL")))
            h.runPending()

            h.coordinator.detach()
            h.attach()
            h.runPending()

            assertThat(h.sent).hasSize(2)
            assertThat(h.last()).isEqualTo(json("""[{"id":"g2-2"},{"type":"trade:us","codes":["AAPL"]}]"""))
        }

    @Test
    fun `does not wait out the rate limit on a fresh connection`() =
        runTest {
            val h = Harness(this)
            h.attach()
            h.coordinator.add(listOf(trade("AAPL")))
            h.runPending()

            h.coordinator.detach()
            h.attach()

            assertThat(h.runPending()).isEqualTo(50.milliseconds)
        }

    @Test
    fun `leaves a rejected topic out of the next declaration`() =
        runTest {
            val h = Harness(this)
            h.attach()
            h.coordinator.add(listOf(trade("AAPL"), trade("NOPE")))
            h.runPending()

            h.coordinator.handleFrame(ack("g1-1", listOf("trade:us:AAPL"), listOf("trade:us:NOPE")))
            h.coordinator.add(listOf(trade("TSLA")))
            h.runPending()

            assertThat(h.last()).isEqualTo(json("""[{"id":"g1-2"},{"type":"trade:us","codes":["AAPL","TSLA"]}]"""))
            assertThat(h.coordinator.snapshot().rejected).containsExactly("trade:us:NOPE")
        }

    @Test
    fun `keeps a rejection across reconnects, so a bad symbol does not repeat forever`() =
        runTest {
            val h = Harness(this)
            h.attach()
            h.coordinator.add(listOf(trade("AAPL"), trade("NOPE")))
            h.runPending()
            h.coordinator.handleFrame(ack("g1-1", listOf("trade:us:AAPL"), listOf("trade:us:NOPE")))

            h.coordinator.detach()
            h.attach()
            h.runPending()

            assertThat(h.last()).isEqualTo(json("""[{"id":"g2-2"},{"type":"trade:us","codes":["AAPL"]}]"""))
        }

    @Test
    fun `keeps a rejection when the same topic is merely added again`() =
        runTest {
            // Startup re-declares everything, so add() must not be permission to retry.
            val h = Harness(this)
            h.attach()
            h.coordinator.add(listOf(trade("NOPE")))
            h.runPending()
            h.coordinator.handleFrame(ack("g1-1", emptyList(), listOf("trade:us:NOPE")))

            h.coordinator.add(listOf(trade("NOPE")))
            h.runPending()

            assertThat(h.coordinator.snapshot().rejected).containsExactly("trade:us:NOPE")
        }

    @Test
    fun `treats retry as permission to try a rejected topic again`() =
        runTest {
            val h = Harness(this)
            h.attach()
            h.coordinator.add(listOf(trade("NOPE")))
            h.runPending()
            h.coordinator.handleFrame(ack("g1-1", emptyList(), listOf("trade:us:NOPE")))

            h.coordinator.retry(listOf(trade("NOPE")))
            h.runPending()

            assertThat(h.coordinator.snapshot().rejected).isEmpty()
            assertThat(h.last()).isEqualTo(json("""[{"id":"g1-2"},{"type":"trade:us","codes":["NOPE"]}]"""))
        }

    @Test
    fun `ignores an ack from a connection that is already gone`() =
        runTest {
            val h = Harness(this)
            h.attach()
            h.coordinator.add(listOf(trade("AAPL")))
            h.runPending()

            h.coordinator.detach()
            h.attach()
            // Arrives late, addressed to generation 1 while generation 2 is live.
            h.coordinator.handleFrame(ack("g1-1", emptyList(), listOf("trade:us:AAPL")))

            assertThat(h.coordinator.snapshot().rejected).isEmpty()
        }

    @Test
    fun `refuses an add that would exceed the per-connection limit`() =
        runTest {
            val h = Harness(this)
            h.attach()
            h.coordinator.add(List(100) { trade("S$it") })

            assertThatThrownBy { h.coordinator.add(listOf(trade("ONE-TOO-MANY"))) }
                .isInstanceOf(TopicCapacityError::class.java)
            // The refused add must not have changed anything.
            assertThat(h.coordinator.snapshot().desired).hasSize(100)
        }

    @Test
    fun `clears everything with a bare empty array rather than leaving the set behind`() =
        runTest {
            val h = Harness(this)
            h.attach()
            h.coordinator.add(listOf(trade("AAPL")))
            h.runPending()

            h.coordinator.remove(listOf(trade("AAPL")))
            h.runPending()

            assertThat(h.sent[1]).isEqualTo("[]")
        }

    @Test
    fun `drops a pending declaration on detach instead of sending it to a dead socket`() =
        runTest {
            val h = Harness(this)
            h.attach()
            h.coordinator.add(listOf(trade("AAPL")))

            h.coordinator.detach()

            assertThat(h.runPending()).isNull()
        }

    @Test
    fun `does nothing before a connection exists`() =
        runTest {
            val h = Harness(this)
            h.coordinator.add(listOf(trade("AAPL")))

            assertThat(h.runPending()).isNull()
            assertThat(h.coordinator.snapshot().desired).containsExactly("trade:us:AAPL")
        }

    @Test
    fun `ignores frames that are not acks`() =
        runTest {
            val h = Harness(this)
            h.attach()
            h.coordinator.handleFrame(ServerFrame.Pong)
            h.coordinator.handleFrame(ServerFrame.Unknown("???"))

            assertThat(h.coordinator.snapshot().rejected).isEmpty()
        }

    @Test
    fun `persists a rejection, and seeds from the store so a restart does not re-learn it`() =
        runTest {
            val store = MemoryRejections()
            val first = Harness(this, store)
            first.attach()
            first.coordinator.add(listOf(trade("AAPL"), trade("NOPE")))
            first.runPending()
            first.coordinator.handleFrame(ack("g1-1", listOf("trade:us:AAPL"), listOf("trade:us:NOPE")))
            runCurrent()

            val restarted = Harness(this, store)
            restarted.coordinator.load()
            restarted.coordinator.add(listOf(trade("AAPL"), trade("NOPE")))
            restarted.attach()
            restarted.runPending()

            assertThat(store.targets).containsExactly("trade:us:NOPE")
            assertThat(restarted.last()).isEqualTo(json("""[{"id":"g1-1"},{"type":"trade:us","codes":["AAPL"]}]"""))
        }

    @Test
    fun `keeps the rejection in memory when the store fails`() =
        runTest {
            val h = Harness(this, FailingRejections)
            h.attach()
            h.coordinator.add(listOf(trade("NOPE")))
            h.runPending()

            h.coordinator.handleFrame(ack("g1-1", emptyList(), listOf("trade:us:NOPE")))
            runCurrent()

            assertThat(h.coordinator.snapshot().rejected).containsExactly("trade:us:NOPE")
            assertThat(h.storeErrors).hasSize(1)
        }

    private class MemoryRejections : RejectionStore {
        val targets = mutableListOf<String>()

        override suspend fun rejectedTopics() = targets.toList()

        override suspend fun recordRejection(
            target: String,
            code: String,
            at: Instant,
        ) {
            targets += target
        }

        override suspend fun clearRejection(target: String) {
            targets -= target
        }
    }

    private object FailingRejections : RejectionStore {
        override suspend fun rejectedTopics() = emptyList<String>()

        override suspend fun recordRejection(
            target: String,
            code: String,
            at: Instant,
        ): Unit = error("disk full")

        override suspend fun clearRejection(target: String) = Unit
    }
}
