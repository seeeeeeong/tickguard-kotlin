package tickguard.tools

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import tickguard.stream.Decimal
import tickguard.trading.SleeveProposal
import tickguard.trading.TestSleeves
import tickguard.trading.attribute
import tickguard.trading.position
import tickguard.trading.proposeRebalance
import java.time.LocalDate
import kotlin.system.exitProcess

/**
 * Previews what the service will propose for the test sleeves, from the ledger
 * and the stored bars: the same function, run by hand.
 *
 *   ./gradlew :tools:sleeveProposal --args="1368.6"
 *
 * The argument is the KRW per USD rate, for showing amounts in won; the tool
 * does not call Toss, so it can run beside the service.
 */
suspend fun main(args: Array<String>) {
    val fx = args.getOrNull(0)?.toBigDecimalOrNull()?.let { Decimal.parse(it.toPlainString(), "fx") }
    if (fx == null) {
        System.err.println("usage: sleeveProposal KRW_PER_USD")
        exitProcess(2)
    }
    val store = openStore(environment())
    try {
        val today = LocalDate.now()
        val orders = store.ordersSince(TestSleeves.START)
        val owned = attribute(orders, TestSleeves.ALL, store.orderTags(), TestSleeves.PERSONAL, TestSleeves.START)
        for (sleeve in TestSleeves.ALL) {
            val bars = sleeve.universe.associateWith { store.bars(it, today.minusYears(2), today.minusDays(1)) }
            val proposal = proposeRebalance(sleeve, position(sleeve, owned[sleeve.id].orEmpty()), bars)
            println(proposal?.let { text(it, fx) } ?: "== ${sleeve.name}: 일봉 없음")
        }
    } finally {
        withContext(NonCancellable) { store.close() }
    }
}

fun text(
    proposal: SleeveProposal,
    fx: Decimal,
): String {
    val lines = ArrayList<String>()
    val sleeve = proposal.sleeve
    lines +=
        "== ${sleeve.name} · \$${proposal.value.format(2)} (자본 \$${sleeve.capital.format(2)}) · 기준 ${proposal.asOf} 종가"
    proposal.stopped?.let { lines += "   손실 한도: $it" }
    lines +=
        "   목표: " +
        proposal.targets.filterValues { it.signum() > 0 }.entries.joinToString(
            ", ",
        ) { "${it.key} ${pct(it.value)}" }
    for (trade in proposal.trades) {
        val code = trade.code.padEnd(CODE_WIDTH)
        val quantity = trade.quantity.toPlainString().padStart(QUANTITY_WIDTH)
        val krw = (trade.value * fx).format(0)
        lines +=
            "   ${trade.side} $code ${quantity}주  ≈ \$${trade.value.format(
                2,
            )} (${krw}원) @ ${trade.price.toPlainString()}"
    }
    if (proposal.trades.isEmpty()) lines += "   (거래 없음)"
    return lines.joinToString("\n")
}

/** Wide enough for a US ticker like BRK.B. */
private const val CODE_WIDTH = 6

/** Wide enough for a fractional quantity to six places. */
private const val QUANTITY_WIDTH = 10

private fun pct(weight: Decimal) = "${(weight * Decimal.HUNDRED).format(1)}%"
