package tickguard.testing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tickguard.news.NewsItem
import tickguard.news.NewsSourceName
import tickguard.rules.Outcome
import tickguard.rules.Rule
import tickguard.rules.RuleContext
import tickguard.rules.RuleEngine
import tickguard.rules.Signal
import tickguard.store.NewsKey
import tickguard.store.Store
import tickguard.store.TickRow
import tickguard.subscribe.SubscriptionCoordinator
import tickguard.subscribe.Topic
import tickguard.verdict.Direction
import tickguard.verdict.Verdict
import java.time.Instant
import java.time.InstantSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * What any store must do, run against each adapter in turn. SQLite and the
 * database that replaces it answer the same tests, so moving between them is a
 * change of adapter and not of behaviour.
 *
 * A backing gives each test an empty store that can be opened again on the
 * same data, which is how a restart is simulated.
 */
abstract class StoreContract {
    interface Backing {
        suspend fun open(): Store

        fun dispose()
    }

    protected abstract fun backing(): Backing

    protected class Handle(
        private val backing: Backing,
        var store: Store,
    ) {
        suspend fun restart() {
            store.close()
            store = backing.open()
        }
    }

    protected fun contract(block: suspend TestScope.(Handle) -> Unit) =
        runTest {
            val backing = backing()
            val handle = Handle(backing, backing.open())
            try {
                block(handle)
            } finally {
                handle.store.close()
                backing.dispose()
            }
        }

    private fun at(ms: Long): Instant = Instant.ofEpochMilli(ms)

    private fun signal(
        firedAtMs: Long,
        code: String = "005930",
    ) = Signal("drawdown-7pct", code, "t", "d", at(firedAtMs))

    @Test
    fun `fires and rejections survive a restart`() =
        contract { h ->
            h.store.recordFire("k", signal(1_000))
            h.store.recordRejection("trade:us:NOPE", "stock-not-found", at(500))
            h.restart()

            assertThat(h.store.firesSince(at(0))).containsExactlyEntriesOf(mapOf("k" to at(1_000)))
            assertThat(h.store.rejectedTopics()).containsExactly("trade:us:NOPE")
        }

    @Test
    fun `keeps only the latest fire for a key`() =
        contract { h ->
            h.store.recordFire("k", signal(1_000))
            h.store.recordFire("k", signal(2_000))

            assertThat(h.store.firesSince(at(0))).containsExactlyEntriesOf(mapOf("k" to at(2_000)))
            assertThat(h.store.recentSignals(10)).hasSize(1)
        }

    @Test
    fun `reads only fires recent enough to still suppress`() =
        contract { h ->
            h.store.recordFire("old", signal(1_000))
            h.store.recordFire("new", signal(9_000))

            assertThat(h.store.firesSince(at(5_000))).containsExactlyEntriesOf(mapOf("new" to at(9_000)))
        }

    @Test
    fun `drops fires that can no longer suppress anything`() =
        contract { h ->
            h.store.recordFire("old", signal(1_000))
            h.store.recordFire("new", signal(9_000))

            assertThat(h.store.pruneFires(at(5_000))).isEqualTo(1)
            assertThat(h.store.firesSince(at(0)).keys).containsExactly("new")
        }

    @Test
    fun `clears a rejection so the topic can be tried again`() =
        contract { h ->
            h.store.recordRejection("trade:us:NOPE", "stock-not-found", at(1))
            h.store.clearRejection("trade:us:NOPE")

            assertThat(h.store.rejectedTopics()).isEmpty()
        }

    @Test
    fun `returns signals newest first, for measuring a rule later`() =
        contract { h ->
            h.store.recordFire("a", signal(1_000, "A"))
            h.store.recordFire("b", signal(3_000, "B"))

            assertThat(h.store.recentSignals(10).map { it.code }).containsExactly("B", "A")
        }

    private val always =
        object : Rule {
            override val id = "always"
            override val holdFor = Duration.ZERO
            override val cooldown = 1.minutes

            override fun dedupeKey(context: RuleContext) = context.trade.code

            override fun evaluate(context: RuleContext) = Outcome(context.trade.code, "t", "d")
        }

    /** Fires once at [clockMs] and waits for the write-behind to land. */
    private suspend fun fireOnce(
        store: Store,
        clockMs: Long,
    ): List<Signal> {
        val signals = mutableListOf<Signal>()
        val writes = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val engine =
            RuleEngine(
                rules = listOf(always),
                clock = InstantSource { at(clockMs) },
                onSignal = { signals += it },
                cooldowns = store,
                scope = writes,
            )
        engine.load()
        engine.evaluate(tick("005930", "100"))
        writes.coroutineContext.job.children
            .toList()
            .joinAll()
        return signals
    }

    @Test
    fun `does not re-fire a rule that is still cooling down`() =
        contract { h ->
            assertThat(fireOnce(h.store, 0)).hasSize(1)

            // A fresh process ten seconds later, as a deploy would produce.
            h.restart()

            assertThat(fireOnce(h.store, 10_000)).isEmpty()
        }

    @Test
    fun `fires again once the cooldown has genuinely passed`() =
        contract { h ->
            fireOnce(h.store, 0)
            h.restart()

            assertThat(fireOnce(h.store, 61_000)).hasSize(1)
        }

    @Test
    fun `leaves a known-bad topic out of the first declaration after a restart`() =
        contract { h ->
            h.store.recordRejection("trade:us:NOPE", "stock-not-found", at(0))
            h.restart()

            val sent = mutableListOf<String>()
            val coordinator =
                SubscriptionCoordinator(backgroundScope, VirtualClock(testScheduler), rejections = h.store)
            coordinator.load()
            coordinator.attach { sent += it }
            coordinator.add(listOf(Topic("trade:us", "AAPL"), Topic("trade:us", "NOPE")))
            advanceTimeBy(1.seconds)
            runCurrent()

            // Without the store this would declare NOPE and be rejected all over again.
            assertThat(sent.last()).contains("AAPL").doesNotContain("NOPE")
        }

    private fun row(
        tradedAtMs: Long = 1_000,
        price: String = "340.39",
        volume: String = "2",
        code: String = "AAPL",
    ) = TickRow("trade:us", code, price, volume, "USD", at(tradedAtMs), at(tradedAtMs + 50))

    @Test
    fun `keeps price and volume as the exact strings it was given`() =
        contract { h ->
            // A REAL column would store approximations; the exact digits must survive.
            h.store.recordTicks(listOf(row(price = "350.0055", volume = "0.0000001")))

            val stored = h.store.ticksBetween("AAPL", at(0), at(2_000)).single()

            assertThat(stored.price).isEqualTo("350.0055")
            assertThat(stored.volume).isEqualTo("0.0000001")
        }

    @Test
    fun `reads one symbol over a half-open range, oldest first`() =
        contract { h ->
            h.store.recordTicks(listOf(row(3_000, price = "3")))
            h.store.recordTicks(listOf(row(1_000, price = "1")))
            h.store.recordTicks(listOf(row(2_000, price = "2")))
            h.store.recordTicks(listOf(row(1_500, code = "NVDA")))

            assertThat(h.store.ticksBetween("AAPL", at(1_000), at(3_000)).map { it.price }).containsExactly("1", "2")
        }

    @Test
    fun `keeps identical trades as separate rows, in arrival order`() =
        contract { h ->
            // No sequence number exists, so two identical ticks are two trades,
            // and within one exchange second only arrival order separates them.
            h.store.recordTicks(listOf(row(volume = "1")))
            h.store.recordTicks(listOf(row(volume = "2")))

            assertThat(h.store.ticksBetween("AAPL", at(0), at(2_000)).map { it.volume }).containsExactly("1", "2")
        }

    @Test
    fun `writes a batch together, in the order given`() =
        contract { h ->
            h.store.recordTicks(listOf(row(volume = "1"), row(volume = "2"), row(volume = "3")))

            assertThat(h.store.ticksBetween("AAPL", at(0), at(2_000)).map { it.volume }).containsExactly("1", "2", "3")
        }

    @Test
    fun `prunes by exchange time and reports how many went`() =
        contract { h ->
            listOf(1_000L, 2_000L, 3_000L).forEach { h.store.recordTicks(listOf(row(it))) }

            assertThat(h.store.pruneTicks(at(2_500))).isEqualTo(2)
            assertThat(h.store.ticksBetween("AAPL", at(0), at(10_000)).map { it.tradedAt }).containsExactly(at(3_000))
        }

    @Test
    fun `ticks survive a restart`() =
        contract { h ->
            h.store.recordTicks(listOf(row()))
            h.restart()

            assertThat(h.store.ticksBetween("AAPL", at(0), at(2_000))).containsExactly(row())
        }

    private fun story(
        id: String = "h1",
        code: String = "AMZN",
        publishedAtMs: Long = 1_000,
    ) = NewsItem(NewsSourceName.GOOGLE_NEWS, id, code, "FTC sues Amazon", "CNBC", "https://x", at(publishedAtMs))

    @Test
    fun `reports a story as new only the first time`() =
        contract { h ->
            assertThat(h.store.recordNews(story(), at(5_000))).isTrue()
            assertThat(h.store.recordNews(story(), at(6_000))).isFalse()
        }

    @Test
    fun `keeps one story as news for each symbol it concerns`() =
        contract { h ->
            h.store.recordNews(story(code = "AMZN"), at(5_000))

            assertThat(h.store.recordNews(story(code = "GOOGL"), at(5_000))).isTrue()
        }

    @Test
    fun `reads a symbol's news newest first, with when it was seen`() =
        contract { h ->
            h.store.recordNews(story(id = "old", publishedAtMs = 1_000), at(5_000))
            h.store.recordNews(story(id = "new", publishedAtMs = 2_000), at(5_000))

            val rows = h.store.newsFor("AMZN", at(0))

            assertThat(rows.map { it.item.id }).containsExactly("new", "old")
            assertThat(rows.first().seenAt).isEqualTo(at(5_000))
        }

    @Test
    fun `prunes news by publication time`() =
        contract { h ->
            h.store.recordNews(story(id = "old", publishedAtMs = 1_000), at(5_000))
            h.store.recordNews(story(id = "new", publishedAtMs = 3_000), at(5_000))

            assertThat(h.store.pruneNews(at(2_000))).isEqualTo(1)
            assertThat(h.store.newsFor("AMZN", at(0)).map { it.item.id }).containsExactly("new")
        }

    private val verdict =
        Verdict(relevant = true, direction = Direction.DOWN, impact = 0.6, summary = "FTC가 아마존을 제소했다.")

    private fun key(id: String = "h1") = NewsKey(NewsSourceName.GOOGLE_NEWS, id, "AMZN")

    @Test
    fun `lists stories without a verdict, newest first, and stops listing a judged one`() =
        contract { h ->
            h.store.recordNews(story(id = "old", publishedAtMs = 1_000), at(5_000))
            h.store.recordNews(story(id = "new", publishedAtMs = 2_000), at(5_000))

            assertThat(
                h.store.pendingVerdicts(at(0), at(10_000), 10).map { it.news.item.id },
            ).containsExactly("new", "old")

            h.store.recordVerdict(key("new"), verdict, "deepseek-flash", at(6_000))

            assertThat(h.store.pendingVerdicts(at(0), at(10_000), 10).map { it.news.item.id }).containsExactly("old")
            assertThat(h.store.verdictFor(key("new"))?.verdict).isEqualTo(verdict)
            assertThat(h.store.verdictFor(key("new"))?.model).isEqualTo("deepseek-flash")
        }

    @Test
    fun `retries a failed story only once its retry is due, and not past the attempt limit`() =
        contract { h ->
            h.store.recordNews(story(), at(5_000))
            h.store.recordVerdictFailure(key(), "timeout", attempts = 1, retryAt = at(8_000))

            assertThat(h.store.pendingVerdicts(at(0), at(7_000), 10)).isEmpty()
            assertThat(
                h.store
                    .pendingVerdicts(at(0), at(8_000), 10)
                    .single()
                    .attempts,
            ).isEqualTo(1)

            h.store.recordVerdictFailure(key(), "timeout", attempts = 5, retryAt = at(8_000))

            assertThat(h.store.pendingVerdicts(at(0), at(9_000), 10)).isEmpty()
        }

    @Test
    fun `counts model calls since a moment, across a restart, and prunes old ones`() =
        contract { h ->
            listOf(1_000L, 2_000L, 3_000L).forEach { h.store.recordModelCall(at(it)) }
            h.restart()

            assertThat(h.store.modelCallsSince(at(2_000))).isEqualTo(2)

            h.store.pruneVerdicts(at(2_500))

            assertThat(h.store.modelCallsSince(at(0))).isEqualTo(1)
        }
}
