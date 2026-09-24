@file:Suppress("MagicNumber") // Column widths of a report laid out as the original laid it out.

package tickguard.tools

import tickguard.backtest.BacktestCase
import tickguard.backtest.DEFAULT_HORIZONS
import tickguard.backtest.Fire
import tickguard.backtest.FireDirection
import tickguard.backtest.drawdownCase
import tickguard.backtest.rapidMoveCase
import tickguard.backtest.replay
import tickguard.backtest.summarise
import tickguard.rules.Position
import tickguard.store.TickRow
import tickguard.stream.Decimal
import tickguard.text.toFixed
import tickguard.time.kstDate
import tickguard.time.kstTime
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Replays recorded ticks through a rule at several thresholds and reports
 * what the price did after each fire.
 *
 *   ./gradlew :tools:backtest                                          # rapid move 1,2,3% over 5m
 *   ./gradlew :tools:backtest --args="--thresholds 0.5,1,2 --window 10m"
 *   ./gradlew :tools:backtest --args="--rule drawdown --average AMZN=281.53 --thresholds 5,7,10"
 *   ./gradlew :tools:backtest --args="--days 7 --list"                 # every fire, not just totals
 *
 * Read-only against the database, so it runs beside the live process. The
 * drawdown rule needs average prices, which are not recorded historically;
 * they are passed in, and replaying today's average over last month is an
 * approximation this prints rather than hides.
 */
fun main(args: Array<String>) {
    val options = BacktestOptions.parse(args.toList())
    val env = environment()
    val since = options.days?.let { Instant.now().minus(Duration.ofDays(it)) } ?: Instant.EPOCH

    println("데이터")
    val firesByCase = options.cases.associate { it.name to mutableListOf<Fire>() }
    openReadOnly(env).use { db ->
        for (code in codesSince(db, since).filter { options.rule != "drawdown" || it in options.positions }) {
            val ticks = ticksOf(db, code, since)
            if (ticks.isEmpty()) continue
            val first = ticks.first().tradedAt
            val last = ticks.last().tradedAt
            val hours = ((last.toEpochMilli() - first.toEpochMilli()) / MILLIS_PER_HOUR).toFixed(1)
            println("  ${code.padEnd(6)} ${"${ticks.size}".padStart(7)}틱  ${at(first)} → ${at(last)} KST (${hours}h)")
            for (case in options.cases) firesByCase.getValue(case.name) += replay(case, ticks, options.positions)
        }
    }
    if (options.rule == "drawdown") println("  ※ 평단은 입력값으로 고정해 재생함. 과거 평단이 달랐다면 결과도 다름")

    printSummary(options.cases, firesByCase)
    if (options.list) printFires(options.cases, firesByCase)
}

private fun printSummary(
    cases: List<BacktestCase>,
    firesByCase: Map<String, List<Fire>>,
) {
    val horizonLabel = DEFAULT_HORIZONS.joinToString("") { "${it.inWholeMinutes}분 뒤".padStart(HORIZON_WIDTH) }
    println("\n${"규칙".padEnd(NAME_WIDTH)} 발화$horizonLabel")
    println(
        "${"".padEnd(NAME_WIDTH + 5)}${DEFAULT_HORIZONS.joinToString("") { "  측정  이어감  평균".padStart(HORIZON_WIDTH) }}",
    )
    for (case in cases) {
        val summary = summarise(case.name, firesByCase.getValue(case.name))
        val cells =
            summary.horizons.joinToString("") {
                "${"${it.measured}".padStart(6)} ${"${it.continued}".padStart(6)} ${pct(it.meanMove)}"
            }
        println("${case.name.padEnd(NAME_WIDTH)} ${"${summary.fires}".padStart(4)}$cells")
    }
    println("\n이어감 = 발화 방향으로 더 움직인 횟수 · 평균 = 발화 방향 기준 평균 변동 (양수면 계속 감)")
}

private fun printFires(
    cases: List<BacktestCase>,
    firesByCase: Map<String, List<Fire>>,
) {
    for (case in cases) {
        for (fire in firesByCase.getValue(case.name)) {
            val after = DEFAULT_HORIZONS.joinToString(" ") { pct(fire.after[it]) }
            val arrow = if (fire.direction == FireDirection.DOWN) "▼" else "▲"
            println("${at(fire.at)}  $arrow ${fire.title.padEnd(32)} $after")
        }
    }
}

private fun codesSince(
    db: Connection,
    since: Instant,
): List<String> =
    db.prepareStatement("SELECT DISTINCT code FROM ticks WHERE traded_at >= ? ORDER BY code").use { statement ->
        statement.setLong(1, since.toEpochMilli())
        statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString("code")) } }
    }

/** In the order they were stored: a second holds several trades, and arrival orders them. */
internal fun ticksOf(
    db: Connection,
    code: String,
    since: Instant = Instant.EPOCH,
): List<TickRow> =
    db
        .prepareStatement(
            """SELECT type, code, price, volume, currency, traded_at, received_at
               FROM ticks WHERE code = ? AND traded_at >= ? ORDER BY traded_at, rowid""",
        ).use { statement ->
            statement.setString(1, code)
            statement.setLong(2, since.toEpochMilli())
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            TickRow(
                                rows.getString("type"),
                                rows.getString("code"),
                                rows.getString("price"),
                                rows.getString("volume"),
                                rows.getString("currency"),
                                Instant.ofEpochMilli(rows.getLong("traded_at")),
                                Instant.ofEpochMilli(rows.getLong("received_at")),
                            ),
                        )
                    }
                }
            }
        }

private fun at(instant: Instant) = "${kstDate(instant).drop(5)} ${kstTime(instant).take(5)}"

private fun pct(value: Decimal?) = if (value == null) "    -" else "${(value * Decimal.HUNDRED).format(2).padStart(6)}%"

internal class BacktestOptions(
    val rule: String,
    val cases: List<BacktestCase>,
    val positions: Map<String, Position>,
    val days: Long?,
    val list: Boolean,
) {
    companion object {
        fun parse(args: List<String>): BacktestOptions {
            val flag = { name: String -> args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) } }
            val rule = flag("--rule") ?: "rapid-move"
            val window = duration(flag("--window") ?: "5m")
            val holdFor = duration(flag("--for") ?: "2m")
            val thresholds = (flag("--thresholds") ?: if (rule == "drawdown") "5,7,10" else "1,2,3").split(',')

            val positions =
                flag("--average")
                    .orEmpty()
                    .split(',')
                    .filter { it.isNotEmpty() }
                    .associate { entry ->
                        val code = entry.substringBefore('=')
                        code to Position(code, Decimal.parse(entry.substringAfter('=', ""), "average"), Decimal.ZERO)
                    }
            require(rule != "drawdown" || positions.isNotEmpty()) {
                "drawdown needs --average CODE=PRICE[,CODE=PRICE]: average prices are not recorded historically"
            }

            val cases =
                thresholds.map { percent ->
                    if (rule == "drawdown") drawdownCase(percent, holdFor) else rapidMoveCase(percent, window)
                }
            return BacktestOptions(rule, cases, positions, flag("--days")?.toLong(), "--list" in args)
        }

        /** `30s`, `5m` or `1h`, as the original's flags took them. */
        fun duration(text: String): kotlin.time.Duration {
            val match =
                DURATION.matchEntire(text)
                    ?: throw IllegalArgumentException("duration must look like 30s, 5m or 1h; got $text")
            val amount = match.groupValues[1].toLong()
            return when (match.groupValues[2]) {
                "s" -> (amount * MILLIS_PER_SECOND).milliseconds
                "m" -> amount.minutes
                else -> (amount * MILLIS_PER_HOUR.toLong()).milliseconds
            }
        }

        /** A count and one unit, nothing else: `90s`, not `1m30s`. */
        private val DURATION = Regex("(\\d+)(s|m|h)")
    }
}

/** Unit arithmetic for durations and the hours column. */
private const val MILLIS_PER_SECOND = 1_000L

/** See [MILLIS_PER_SECOND]. */
private const val MILLIS_PER_HOUR = 3_600_000.0

/** Column widths of the summary table, as the original printed it. */
private const val NAME_WIDTH = 26

/** See [NAME_WIDTH]. */
private const val HORIZON_WIDTH = 20
