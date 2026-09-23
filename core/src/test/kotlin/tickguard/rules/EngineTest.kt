package tickguard.rules

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tickguard.stream.Decimal
import tickguard.testing.tick
import java.time.Instant
import java.time.InstantSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class EngineTest {
    private var now = 0L
    private val clock = InstantSource { Instant.ofEpochMilli(now) }
    private val signals = mutableListOf<Signal>()
    private val ruleErrors = mutableListOf<String>()

    private fun advance(by: Duration) {
        now += by.inWholeMilliseconds
    }

    private fun engine(vararg rules: Rule) =
        RuleEngine(
            rules = rules.toList(),
            clock = clock,
            onSignal = { signals += it },
            onRuleError = { _, id -> ruleErrors += id },
        )

    /** Fires on every tick, so suppression is the only thing under test. */
    private val always = testRule("always", Duration.ZERO, 1.seconds) { Outcome(it.trade.code, "t", "d") }

    /** Holds only while the price is under 100, so a test can make it flicker. */
    private val underHundred =
        testRule("under-100", 2.minutes, 1.hours) {
            if (it.trade.price < Decimal.HUNDRED) Outcome(it.trade.code, "t", "d") else null
        }

    @Test
    fun `suppresses a repeat inside the cooldown`() {
        val engine = engine(always)

        repeat(50) { engine.evaluate(tick("005930", "100")) }

        assertThat(signals).hasSize(1)
        assertThat(engine.stats().suppressed).isEqualTo(49)
    }

    @Test
    fun `fires again once the cooldown has passed`() {
        val engine = engine(always)

        engine.evaluate(tick("005930", "100"))
        advance(999.milliseconds)
        engine.evaluate(tick("005930", "100"))
        advance(1.milliseconds)
        engine.evaluate(tick("005930", "100"))

        assertThat(signals).hasSize(2)
    }

    @Test
    fun `keys cooldowns per symbol, so one noisy name cannot silence another`() {
        val engine = engine(always)

        engine.evaluate(tick("005930", "100"))
        engine.evaluate(tick("000660", "100"))

        assertThat(signals.map { it.code }).containsExactly("005930", "000660")
    }

    @Test
    fun `keeps running the other rules when one throws`() {
        val broken = testRule("broken", Duration.ZERO, Duration.ZERO) { error("boom") }
        val engine = engine(broken, always)

        engine.evaluate(tick("005930", "100"))

        assertThat(signals).hasSize(1)
        assertThat(ruleErrors).containsExactly("broken")
        assertThat(engine.stats().errors).isEqualTo(1)
    }

    @Test
    fun `forgets keys that can no longer suppress anything`() {
        val engine = engine(always)

        repeat(100) { engine.evaluate(tick("CODE$it", "100")) }
        assertThat(engine.stats().trackedKeys).isEqualTo(100)

        advance(10.seconds)
        engine.evaluate(tick("LAST", "100"))

        // Only the entry just written survives: the rest aged past the cooldown.
        assertThat(engine.stats().trackedKeys).isEqualTo(1)
    }

    @Test
    fun `does not fire on a blip through the threshold`() {
        val engine = engine(underHundred)

        // One print under, then straight back over. This is the spread moving.
        engine.evaluate(tick("005930", "99"))
        advance(1.seconds)
        engine.evaluate(tick("005930", "101"))

        assertThat(signals).isEmpty()
        assertThat(engine.stats().pending).isEqualTo(1)
    }

    @Test
    fun `fires once the condition has held for long enough`() {
        val engine = engine(underHundred)

        engine.evaluate(tick("005930", "99"))
        advance(119.seconds)
        engine.evaluate(tick("005930", "99"))
        assertThat(signals).isEmpty()

        advance(1.seconds)
        engine.evaluate(tick("005930", "99"))

        assertThat(signals).hasSize(1)
    }

    @Test
    fun `restarts the clock when the condition stops holding`() {
        val engine = engine(underHundred)

        engine.evaluate(tick("005930", "99"))
        advance(119.seconds)
        // Back over the line: the accumulated time must not count.
        engine.evaluate(tick("005930", "101"))
        advance(1.seconds)
        engine.evaluate(tick("005930", "99"))

        assertThat(signals).isEmpty()
    }

    @Test
    fun `keeps the clock per symbol`() {
        val engine = engine(underHundred)

        engine.evaluate(tick("005930", "99"))
        advance(120.seconds)

        // Both are evaluated at the same instant, but only one has been holding
        // for two minutes. A symbol starting now must not inherit elapsed time.
        engine.evaluate(tick("000660", "99"))
        engine.evaluate(tick("005930", "99"))

        assertThat(signals.map { it.code }).containsExactly("005930")
    }

    @Test
    fun `fires immediately when holdFor is zero`() {
        val engine = engine(testRule("under-100", Duration.ZERO, 1.hours, underHundred::evaluate))

        engine.evaluate(tick("005930", "99"))

        assertThat(signals).hasSize(1)
    }

    @Test
    fun `does not let a pending condition escape the cooldown`() {
        val engine = engine(testRule("under-100", Duration.ZERO, 1.hours, underHundred::evaluate))

        engine.evaluate(tick("005930", "99"))
        advance(1.seconds)
        engine.evaluate(tick("005930", "99"))

        assertThat(signals).hasSize(1)
        assertThat(engine.stats().suppressed).isEqualTo(1)
    }

    @Test
    fun `persists a fire, and seeds from the store so a restart does not re-fire it`() =
        runTest {
            val store = MemoryCooldowns()
            val first =
                RuleEngine(
                    listOf(always),
                    clock = clock,
                    onSignal = { signals += it },
                    cooldowns = store,
                    scope = backgroundScope,
                )
            first.evaluate(tick("005930", "100"))
            runCurrent()

            advance(500.milliseconds)
            val restarted =
                RuleEngine(
                    listOf(always),
                    clock = clock,
                    onSignal = { signals += it },
                    cooldowns = store,
                    scope = backgroundScope,
                )
            restarted.load()
            restarted.evaluate(tick("005930", "100"))

            assertThat(store.fires.keys).containsExactly("always 005930")
            assertThat(signals).hasSize(1)
            assertThat(restarted.stats().suppressed).isEqualTo(1)
        }

    @Test
    fun `keeps suppressing in memory when the store fails`() =
        runTest {
            val failures = mutableListOf<Exception>()
            val engine =
                RuleEngine(
                    listOf(always),
                    clock = clock,
                    onSignal = { signals += it },
                    onStoreError = { failures += it },
                    cooldowns = FailingCooldowns,
                    scope = backgroundScope,
                )

            engine.evaluate(tick("005930", "100"))
            engine.evaluate(tick("005930", "100"))
            runCurrent()

            assertThat(signals).hasSize(1)
            assertThat(failures).isNotEmpty()
        }

    private fun testRule(
        id: String,
        holdFor: Duration,
        cooldown: Duration,
        evaluate: (RuleContext) -> Outcome?,
    ): Rule =
        object : Rule {
            override val id = id
            override val holdFor = holdFor
            override val cooldown = cooldown

            override fun dedupeKey(context: RuleContext) = context.trade.code

            override fun evaluate(context: RuleContext) = evaluate(context)
        }

    private class MemoryCooldowns : CooldownStore {
        val fires = LinkedHashMap<String, Instant>()

        override suspend fun firesSince(since: Instant) = fires.filterValues { it >= since }

        override suspend fun recordFire(
            key: String,
            signal: Signal,
        ) {
            fires[key] = signal.firedAt
        }

        override suspend fun pruneFires(olderThan: Instant): Int {
            val before = fires.size
            fires.entries.removeIf { it.value < olderThan }
            return before - fires.size
        }
    }

    private object FailingCooldowns : CooldownStore {
        override suspend fun firesSince(since: Instant) = emptyMap<String, Instant>()

        override suspend fun recordFire(
            key: String,
            signal: Signal,
        ): Unit = error("disk full")

        override suspend fun pruneFires(olderThan: Instant): Int = error("disk full")
    }
}
