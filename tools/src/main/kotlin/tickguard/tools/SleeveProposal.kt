package tickguard.tools

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import tickguard.stream.Decimal
import tickguard.trading.Bar
import tickguard.trading.History
import tickguard.trading.Sleeve
import tickguard.trading.TestSleeves
import tickguard.trading.proposeTrades
import java.time.LocalDate
import java.util.Locale
import kotlin.system.exitProcess

/**
 * The three test sleeves' trades for a person to place, from the bars in the store.
 *
 *   ./gradlew :tools:sleeveProposal --args="1393.5"
 *
 * The argument is the KRW per USD rate, for showing amounts in won; the tool
 * does not call Toss, so it can run beside the service. Holdings start empty:
 * this proposes the opening trades. Later rebalances run inside the service,
 * which knows what each sleeve holds.
 */
suspend fun main(args: Array<String>) {
    val fx = args.getOrNull(0)?.toBigDecimalOrNull()?.let { Decimal.parse(it.toPlainString(), "fx") }
    if (fx == null) {
        System.err.println("usage: sleeveProposal KRW_PER_USD")
        exitProcess(2)
    }
    val store = openStore(environment())
    try {
        val yesterday = LocalDate.now().minusDays(1)
        for (sleeve in TestSleeves.ALL) {
            val bars =
                sleeve.universe
                    .associateWith {
                        store.bars(it, yesterday.minusYears(2), yesterday)
                    }.filterValues { it.isNotEmpty() }
            println(proposal(sleeve, bars, fx))
        }
    } finally {
        withContext(NonCancellable) { store.close() }
    }
}

/** Vanguard's threshold: a drift under five points is not worth a trade. */
private const val BAND = "0.05"

/** A sleeve's opening proposal as text: signals as of the last bar, then the trades. */
fun proposal(
    sleeve: Sleeve,
    bars: Map<String, List<Bar>>,
    fx: Decimal,
): String {
    if (bars.isEmpty()) return "${sleeve.name}: 일봉 없음"
    val day = bars.values.maxOf { it.last().day }
    val history = History.of(day, bars)
    val targets = sleeve.strategy().targets(history)
    val prices = bars.mapValues { it.value.last().close }
    val capitalUsd = sleeve.capital
    val trades = proposeTrades(targets, emptyMap(), prices, capitalUsd, Decimal.parse(BAND, "band"))
    val lines = ArrayList<String>()
    lines += "== ${sleeve.name} · ${(capitalUsd * fx).format(0)}원 (\$${capitalUsd.format(2)}) · 기준 $day 종가"
    lines +=
        "   목표: " + targets.filterValues { it.signum() > 0 }.entries.joinToString(", ") { "${it.key} ${pct(it.value)}" }
    for (trade in trades) {
        val code = trade.code.padEnd(CODE_WIDTH)
        val quantity = trade.quantity.toPlainString().padStart(QUANTITY_WIDTH)
        val krw = (trade.value * fx).format(0)
        lines +=
            "   ${trade.side} $code ${quantity}주  ≈ \$${trade.value.format(
                2,
            )} (${krw}원) @ ${trade.price.toPlainString()}"
    }
    if (trades.isEmpty()) lines += "   (거래 없음)"
    return lines.joinToString("\n")
}

/** Wide enough for a US ticker like BRK.B. */
private const val CODE_WIDTH = 6

/** Wide enough for a fractional quantity to six places. */
private const val QUANTITY_WIDTH = 10

private fun pct(weight: Decimal) = "${(weight * Decimal.HUNDRED).format(1)}%"
