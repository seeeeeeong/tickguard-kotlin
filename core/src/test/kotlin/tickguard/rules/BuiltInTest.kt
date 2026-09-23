package tickguard.rules

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tickguard.testing.decimal
import tickguard.testing.tick
import java.time.Instant
import java.time.InstantSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class BuiltInTest {
    private val signals = mutableListOf<Signal>()
    private var now = 0L
    private val clock = InstantSource { Instant.ofEpochMilli(now) }

    private fun engine(
        rule: Rule,
        positions: Map<String, Position> = emptyMap(),
        windows: WindowStore? = null,
    ) = RuleEngine(
        listOf(rule),
        positions = { positions },
        windows = windows,
        clock = clock,
        onSignal = { signals += it },
    )

    private fun held(
        code: String,
        averagePrice: String,
    ) = Position(code, decimal(averagePrice), decimal("10"))

    @Test
    fun `drawdown stays quiet for a symbol that is not held`() {
        engine(drawdownFromAverage("0.07", holdFor = Duration.ZERO)).evaluate(tick("005930", "1"))

        assertThat(signals).isEmpty()
    }

    @Test
    fun `drawdown fires below the threshold and not at it`() {
        val engine =
            engine(drawdownFromAverage("0.07", holdFor = Duration.ZERO), mapOf("TSLA" to held("TSLA", "376.35")))

        // 376.35 * 0.93 is exactly 350.0055. A float threshold is 350.00550000000004,
        // which would fire on this tick and be wrong.
        engine.evaluate(tick("TSLA", "350.0055"))
        assertThat(signals).isEmpty()

        engine.evaluate(tick("TSLA", "350.0054"))
        assertThat(signals.single().title).isEqualTo("TSLA 평단 대비 -7.0%")
        assertThat(signals.single().detail).isEqualTo("현재 350.0054 · 평단 376.35 · 보유 10")
        assertThat(signals.single().ruleId).isEqualTo("drawdown-7pct")
    }

    @Test
    fun `large print fires only on a print at or above the size`() {
        val engine = engine(largePrint("1000"))

        engine.evaluate(tick("AAPL", "339.24", "999"))
        assertThat(signals).isEmpty()

        engine.evaluate(tick("AAPL", "339.24", "1000"))
        assertThat(signals.single().title).isEqualTo("AAPL 대량 체결 1000주")
    }

    @Test
    fun `large print states the trade time in Seoul time, not an ISO UTC stamp`() {
        engine(largePrint("1000")).evaluate(tick("AAPL", "339.24", "1000"))

        assertThat(signals.single().detail).isEqualTo("339.24 KRW · 체결 09:00:00")
    }

    private fun rapid(
        maxPoints: Int = 2_000,
        feed: (feed: (price: String, atMs: Long) -> Unit) -> Unit,
    ) {
        val engine = engine(rapidMove("0.03", 5.minutes), windows = WindowStore(maxPoints = maxPoints))
        feed { price, atMs ->
            now = atMs
            engine.evaluate(tick("AAPL", price, at = Instant.ofEpochMilli(atMs)))
        }
    }

    @Test
    fun `rapid move fires when price moves far enough inside the span`() {
        rapid { feed ->
            feed("100", 0)
            feed("101", 3.minutes.inWholeMilliseconds)
            assertThat(signals).isEmpty()

            feed("96", 4.minutes.inWholeMilliseconds)
        }

        assertThat(signals.single().title).isEqualTo("AAPL 240초간 -4.0% 하락")
        assertThat(signals.single().detail).isEqualTo("100 → 96 · 고 101 저 96 · 3틱")
        assertThat(signals.single().ruleId).isEqualTo("rapid-move-3pct-300000ms")
    }

    @Test
    fun `rapid move declines when the retained history does not cover the span`() {
        // Two ticks two seconds apart is not an answer to a five minute question.
        rapid { feed ->
            feed("100", 0)
            feed("90", 2_000)
        }

        assertThat(signals).isEmpty()
    }

    /** Four minutes of prints a second apart, then a 10% drop. */
    private val fourMinutesThenADrop: (feed: (String, Long) -> Unit) -> Unit = { feed ->
        (0 until 240L).forEach { feed("100", it * 1_000) }
        feed("90", 240_000)
    }

    @Test
    fun `rapid move answers when the retained history covers the span`() {
        rapid(feed = fourMinutesThenADrop)

        assertThat(signals).hasSize(1)
    }

    @Test
    fun `rapid move declines the same prints when the count bound trimmed the span short`() {
        // The same four minutes, but the per-symbol cap kept only the last three
        // prints. Answering anyway would read two seconds as a five minute move.
        rapid(maxPoints = 3, feed = fourMinutesThenADrop)

        assertThat(signals).isEmpty()
    }

    @Test
    fun `rapid move needs more than a single print`() {
        rapid { feed -> feed("100", 0) }

        assertThat(signals).isEmpty()
    }
}
