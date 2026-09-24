package tickguard.backtest

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import tickguard.rules.Position
import tickguard.store.TickRow
import tickguard.testing.decimal
import tickguard.text.toFixed
import java.time.Instant
import kotlin.time.Duration.Companion.minutes

private val T0 = Instant.parse("2026-09-23T13:30:00Z")
private val FIVE_MINUTES = 5.minutes

private fun at(minute: Int) = T0.plusSeconds(minute * 60L)

/** One tick a minute from T0, at the given prices. */
private fun minutes(
    prices: List<String>,
    code: String = "AMZN",
) = prices.mapIndexed { minute, price -> TickRow("trade:us", code, price, "1", "USD", at(minute), at(minute)) }

private fun flat(
    n: Int,
    price: String = "100",
) = List(n) { price }

private val AVERAGE_100 = mapOf("AMZN" to Position("AMZN", decimal("100"), decimal("1")))

class ReplayTest {
    @Nested
    inner class Replay {
        @Test
        fun `fires where the live engine would, on market time, and measures what followed`() {
            // Flat, a 4% drop inside five minutes, then further down.
            val ticks = minutes(flat(6) + listOf("98", "96", "95") + flat(5, "94") + flat(30, "93"))

            val fire = replay(rapidMoveCase("3", FIVE_MINUTES), ticks).single()

            assertThat(fire.code).isEqualTo("AMZN")
            assertThat(fire.direction).isEqualTo(FireDirection.DOWN)
            assertThat(fire.at).isEqualTo(at(7))
            // 96 → 94 five minutes later: continued down by 2.08%, reported positive.
            assertThat(fire.after[FIVE_MINUTES]?.format(4)).isEqualTo("0.0208")
        }

        @Test
        fun `reports a move that reversed as negative`() {
            val ticks = minutes(flat(6) + listOf("102", "104") + flat(10, "100"))

            val fire = replay(rapidMoveCase("3", FIVE_MINUTES), ticks).first()

            assertThat(fire.direction).isEqualTo(FireDirection.UP)
            assertThat(fire.after.getValue(FIVE_MINUTES)?.signum()).isNegative()
        }

        @Test
        fun `fires once per cooldown while a move keeps qualifying`() {
            // Every tick from minute 6 to 10 qualifies; the cooldown equals the
            // window, so only the first fires. A sixth minute would open a new window.
            val ticks = minutes(flat(6) + listOf("97", "96", "95", "94", "93"))

            assertThat(replay(rapidMoveCase("3", FIVE_MINUTES), ticks)).hasSize(1)
        }

        @Test
        fun `fires again once the cooldown has passed and the move goes on`() {
            val ticks = minutes(flat(6) + listOf("97", "96", "95", "94", "93", "92"))

            assertThat(replay(rapidMoveCase("3", FIVE_MINUTES), ticks).map { it.at }).containsExactly(at(6), at(11))
        }

        @Test
        fun `leaves a horizon past the end of the data unmeasured rather than flat`() {
            val ticks = minutes(flat(6) + listOf("97", "96") + flat(6, "95"))
            val fire = replay(rapidMoveCase("3", FIVE_MINUTES), ticks).first()

            assertThat(fire.after[FIVE_MINUTES]).isNotNull()
            assertThat(fire.after[60.minutes]).isNull()
            assertThat(summarise("x", listOf(fire)).horizons.map { it.measured }).containsExactly(1, 0, 0)
        }

        @Test
        fun `holds a drawdown for its duration before firing, as live`() {
            // -8% for one minute then back: a blip. Then -8% held for three.
            val ticks = minutes(listOf("100", "92", "100", "100", "92", "92", "92", "92"))

            val fires = replay(drawdownCase("7", 2.minutes), ticks, AVERAGE_100)

            assertThat(fires.map { it.at }).containsExactly(at(6))
        }

        @Test
        fun `replays each symbol without leaking state into the next`() {
            val amzn = replay(rapidMoveCase("3", FIVE_MINUTES), minutes(flat(6) + listOf("97", "96"), "AMZN"))
            val googl = replay(rapidMoveCase("3", FIVE_MINUTES), minutes(flat(10), "GOOGL"))

            assertThat(listOf(amzn.size, googl.size)).containsExactly(1, 0)
        }
    }

    @Test
    fun `summarise counts continuations and averages the signed move`() {
        val ticks = minutes(flat(6) + listOf("98", "96", "95") + flat(5, "94") + flat(30, "93"))
        val summary = summarise("rapid 3%", replay(rapidMoveCase("3", FIVE_MINUTES), ticks))

        assertThat(summary.fires).isEqualTo(1)
        assertThat(summary.horizons[0].measured).isEqualTo(1)
        assertThat(summary.horizons[0].continued).isEqualTo(1)
        assertThat(summary.horizons[0].meanMove?.format(4)).isEqualTo("0.0208")
    }

    /**
     * freqtrade's check, as a property: replay the whole history, then replay
     * it cut short at each fire. Every fire up to the cut must be the same in
     * both. A rule that read the future — a window trimmed by a later tick, a
     * hold measured against a later clock — would fire differently once the
     * future is gone. The forward outcomes are excluded: they read the future
     * on purpose.
     */
    @ParameterizedTest(name = "{0} fires the same with the history cut at each of its fires")
    @MethodSource("cases")
    fun `no look-ahead`(
        name: String,
        case: BacktestCase,
    ) {
        // Bought above where the walk starts, so a 5% drawdown is common enough
        // to fire several times across the history.
        val positions = mapOf("AMZN" to Position("AMZN", decimal("110"), decimal("1")))
        val ticks = randomWalk(42, 2_000)
        val full = replay(case, ticks, positions)
        assertThat(full.size).describedAs(name).isGreaterThan(2)

        for (fire in full) {
            val upTo = ticks.indexOfFirst { it.tradedAt == fire.at } + 1
            val partial = replay(case, ticks.subList(0, upTo), positions)

            assertThat(partial.map(::signature)).isEqualTo(full.filter { it.at <= fire.at }.map(::signature))
        }
    }

    private fun signature(fire: Fire) = "${fire.at} ${fire.direction} ${fire.title}"

    /** The original's walk, in the same double arithmetic, so it is the same walk. */
    private fun randomWalk(
        seed: Int,
        count: Int,
    ): List<TickRow> {
        var state = seed.toDouble()
        val random = {
            state = (state * 1_103_515_245.0 + 12_345.0) % 2_147_483_648.0
            state / 2_147_483_648.0
        }
        var price = 100.0
        return List(count) { i ->
            // Mostly small steps, with an occasional jump so rules actually fire.
            val step = (random() - 0.5) * 0.004
            val jump = if (random() < 0.01) (random() - 0.5) * 0.08 else 0.0
            price *= 1 + step + jump
            val at = T0.plusMillis(i * 15_000L)
            TickRow("trade:us", "AMZN", price.toFixed(2), "1", "USD", at, at)
        }
    }

    companion object {
        @JvmStatic
        fun cases() =
            listOf(
                Arguments.of("rapid move 1% / 5m", rapidMoveCase("1", FIVE_MINUTES)),
                Arguments.of("drawdown 5% / hold 2m", drawdownCase("5", 2.minutes)),
            )
    }
}
