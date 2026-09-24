package tickguard.backtest

import tickguard.rules.Position
import tickguard.rules.Rule
import tickguard.rules.RuleEngine
import tickguard.rules.WindowStore
import tickguard.rules.drawdownFromAverage
import tickguard.rules.rapidMove
import tickguard.store.TickRow
import tickguard.stream.Decimal
import tickguard.stream.Trade
import tickguard.stream.changeRatio
import java.time.Instant
import java.time.InstantSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

// Replays stored ticks through the real rule engine and asks, for every fire,
// what the price did next.
//
// The engine is the production one, not a reimplementation, with its clock
// set to each tick's exchange time: the hold-for duration, the cooldown and
// the price window all run on market time, so an afternoon replays in
// seconds and fires exactly where it would have fired live. Cooldowns live
// in memory, so a replay never touches the running process's state.
//
// "What next" is the price a fixed time after the fire, taken from the first
// tick at or after it. A fire too close to the end of the data, or to a
// market close, has no answer for the longer horizons and is left out of
// them rather than counted as flat.
//
// Each symbol replays on its own engine: rule state is per symbol anyway,
// and a fresh clock per symbol keeps time moving forward.

enum class FireDirection(
    val wire: String,
) {
    UP("up"),
    DOWN("down"),
}

class BacktestCase(
    val name: String,
    val rule: Rule,
    /** Which way the fire pointed, read from the state the rule saw. */
    val direction: (Trade, WindowStore) -> FireDirection,
)

data class Fire(
    val case: String,
    val code: String,
    val at: Instant,
    val price: Decimal,
    val direction: FireDirection,
    val title: String,
    /** Signed so that positive means the move continued. Null past the data. */
    val after: Map<Duration, Decimal?>,
)

data class HorizonSummary(
    val horizon: Duration,
    /** Fires with a price at this horizon. */
    val measured: Int,
    val continued: Int,
    /** Mean of the signed moves, as a ratio. */
    val meanMove: Decimal?,
)

data class CaseSummary(
    val name: String,
    val fires: Int,
    val horizons: List<HorizonSummary>,
)

/** Five minutes, half an hour, an hour: an alert's news value, and whether it was a turn or a trend. */
val DEFAULT_HORIZONS = listOf(5.minutes, 30.minutes, 60.minutes)

fun rapidMoveCase(
    percent: String,
    window: Duration,
) = BacktestCase(
    name = "rapid-move $percent% / ${minutesText(window)}m",
    rule = rapidMove(fractionOf(percent), window),
    direction = { trade, windows ->
        val recent = windows.snapshot(trade.code, window)
        if (recent != null && recent.last < recent.open) FireDirection.DOWN else FireDirection.UP
    },
)

fun drawdownCase(
    percent: String,
    holdFor: Duration,
) = BacktestCase(
    name = "drawdown $percent% / hold ${minutesText(holdFor)}m",
    rule = drawdownFromAverage(fractionOf(percent), holdFor),
    direction = { _, _ -> FireDirection.DOWN },
)

fun toTrade(row: TickRow) =
    Trade(
        type = row.type,
        code = row.code,
        price = Decimal.parse(row.price, "price"),
        volume = Decimal.parse(row.volume, "volume"),
        at = row.tradedAt,
        currency = row.currency,
    )

/** One symbol's ticks, oldest first, through one case. */
fun replay(
    case: BacktestCase,
    ticks: List<TickRow>,
    positions: Map<String, Position> = emptyMap(),
    horizons: List<Duration> = DEFAULT_HORIZONS,
): List<Fire> {
    val trades = ticks.map(::toTrade)
    val windows = WindowStore()
    val fired = mutableListOf<Pair<Int, Fire>>()

    var clock = Instant.EPOCH
    var index = 0
    val engine =
        RuleEngine(
            rules = listOf(case.rule),
            positions = { positions },
            windows = windows,
            clock = InstantSource { clock },
            onSignal = { signal ->
                val trade = trades[index]
                fired +=
                    index to
                    Fire(
                        case.name,
                        trade.code,
                        clock,
                        trade.price,
                        case.direction(trade, windows),
                        signal.title,
                        emptyMap(),
                    )
            },
        )

    while (index < trades.size) {
        clock = trades[index].at
        engine.evaluate(trades[index])
        index += 1
    }

    return fired.map { (firedAt, fire) ->
        fire.copy(after = horizons.associateWith { moveAfter(trades, firedAt, fire, it) })
    }
}

private fun moveAfter(
    trades: List<Trade>,
    from: Int,
    fire: Fire,
    horizon: Duration,
): Decimal? {
    val target = fire.at.plus(horizon.toJavaDuration())
    val later = trades.subList(from + 1, trades.size).firstOrNull { it.at >= target } ?: return null
    val move = changeRatio(fire.price, later.price)
    return if (fire.direction == FireDirection.DOWN) -move else move
}

fun summarise(
    name: String,
    fires: List<Fire>,
    horizons: List<Duration> = DEFAULT_HORIZONS,
) = CaseSummary(
    name = name,
    fires = fires.size,
    horizons =
        horizons.map { horizon ->
            val moves = fires.mapNotNull { it.after[horizon] }
            HorizonSummary(
                horizon = horizon,
                measured = moves.size,
                continued = moves.count { it.signum() > 0 },
                meanMove =
                    if (moves.isEmpty()) {
                        null
                    } else {
                        moves.fold(Decimal.ZERO, Decimal::plus) /
                            Decimal.of(moves.size.toLong())
                    },
            )
        },
)

private fun fractionOf(percent: String) = (Decimal.parse(percent, "percent") / Decimal.HUNDRED).toPlainString()

/** As the original wrote `windowMs / 60_000` into a name: `5`, or `0.5` for thirty seconds. */
private fun minutesText(duration: Duration): String {
    val millis = duration.inWholeMilliseconds
    return if (millis % MILLIS_PER_MINUTE ==
        0L
    ) {
        "${millis / MILLIS_PER_MINUTE}"
    } else {
        "${millis / MILLIS_PER_MINUTE.toDouble()}"
    }
}

/** Unit arithmetic for a case's name. */
private const val MILLIS_PER_MINUTE = 60_000L
