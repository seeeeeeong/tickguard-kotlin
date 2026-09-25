package tickguard.tools

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import tickguard.store.Store
import tickguard.store.postgres.PostgresStore
import tickguard.store.sqlite.SqliteStore
import tickguard.stream.Decimal
import tickguard.trading.Bar
import tickguard.trading.BuyAndHold
import tickguard.trading.CostModel
import tickguard.trading.MomentumRotation
import tickguard.trading.Strategy
import tickguard.trading.TrendFilter
import tickguard.trading.simulate
import java.time.LocalDate
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Compares the candidate strategies with holding, on the bars in the store.
 *
 *   ./gradlew :tools:strategyReport --args="AAPL,GOOGL,AMZN 2016-01-01 0.001,0.0000278,0.0005"
 *
 * The costs are commission, sell tax and slippage, each a fraction, and are
 * required: a result with made-up costs is a made-up result. Each strategy
 * runs over the whole span and again over each half on its own; one that
 * wins only in one half is a result to distrust.
 *
 * The symbols are the caller's choice, and a list of today's holdings is a
 * list of survivors: the report says so rather than pretending otherwise.
 */
suspend fun main(args: Array<String>) {
    val codes =
        args
            .getOrNull(0)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
    val costs = args.getOrNull(2)?.let(::parseCosts)
    if (codes.isNullOrEmpty() || costs == null) {
        System.err.println("usage: strategyReport SYMBOL[,SYMBOL…] FROM COMMISSION,SELL_TAX,SLIPPAGE")
        exitProcess(2)
    }
    val from = LocalDate.parse(args[1])
    val store = openStore(environment())
    try {
        // Up to yesterday: today's bar may still be forming (Toss trades US shares
        // in Korean daytime), and a partial close is not a price anyone decided on.
        val bars =
            codes
                .associateWith {
                    store.bars(
                        it,
                        from,
                        LocalDate.now().minusDays(1),
                    )
                }.filterValues { it.isNotEmpty() }
        println(report(bars, costs, Decimal.of(STARTING_CASH), candidates()))
    } finally {
        withContext(NonCancellable) { store.close() }
    }
}

/** What each run starts with. Returns are ratios, so the amount only needs to buy whole shares. */
private const val STARTING_CASH = 100_000_000L

fun candidates(): List<() -> Strategy> = listOf(::BuyAndHold, { TrendFilter() }, { MomentumRotation() })

/** `commission,sellTax,slippage`, each a fraction; null if it does not read as three numbers. */
fun parseCosts(text: String): CostModel? {
    val fields = text.split(",")
    val parts =
        fields.mapNotNull {
            it.trim().toBigDecimalOrNull()?.let { n ->
                Decimal.parse(n.toPlainString(), "cost")
            }
        }
    if (fields.size != COST_PARTS || parts.size != COST_PARTS) return null
    val (commission, sellTax, slippage) = parts
    return CostModel(commission = commission, sellTax = sellTax, slippage = slippage)
}

/** Commission, sell tax and slippage. */
private const val COST_PARTS = 3

/** A table of every strategy over the whole span and each half, one strategy instance per run. */
fun report(
    bars: Map<String, List<Bar>>,
    costs: CostModel,
    cash: Decimal,
    strategies: List<() -> Strategy>,
): String {
    val days =
        bars.values
            .flatten()
            .map { it.day }
            .distinct()
            .sorted()
    if (days.isEmpty()) return "데이터 없음"
    val middle = days[days.size / 2]
    val periods =
        listOf(
            "전체" to { _: LocalDate -> true },
            "전반" to { day: LocalDate -> day < middle },
            "후반" to { day: LocalDate -> day >= middle },
        )
    val lines = ArrayList<String>()
    lines += "기간 ${days.first()} → ${days.last()} · 종목 ${bars.keys.joinToString(",")}"
    lines += "비용: 수수료 ${costs.commission} · 매도세 ${costs.sellTax} · 슬리피지 ${costs.slippage}"
    lines += "※ 종목을 오늘 기준으로 골랐다면 살아남은 종목만 본 결과(생존 편향)"
    lines += ""
    lines += "%-24s %-4s %9s %9s %9s %6s %6s".format(Locale.US, "전략", "기간", "수익률", "연환산", "최대낙폭", "샤프", "거래")
    for (make in strategies) {
        for ((label, keep) in periods) {
            val slice = bars.mapValues { (_, list) -> list.filter { keep(it.day) } }.filterValues { it.isNotEmpty() }
            val run = simulate(slice, make(), costs, cash)
            val p = run.performance
            lines +=
                "%-24s %-4s %8.1f%% %8.1f%% %8.1f%% %6.2f %6d".format(
                    Locale.US,
                    run.strategy,
                    label,
                    p.totalReturn * PERCENT,
                    p.cagr * PERCENT,
                    p.maxDrawdown * PERCENT,
                    p.sharpe,
                    run.fills.size,
                )
        }
    }
    return lines.joinToString("\n")
}

/** A ratio times this is a percent. */
private const val PERCENT = 100

private fun openStore(env: Map<String, String>): Store {
    val url = env["TICKGUARD_PG_URL"]
    return if (url.isNullOrEmpty()) {
        SqliteStore.open(env["TICKGUARD_DB"] ?: "tickguard.db")
    } else {
        PostgresStore.open(url, env.getValue("TICKGUARD_PG_USER"), env.getValue("TICKGUARD_PG_PASSWORD"))
    }
}
