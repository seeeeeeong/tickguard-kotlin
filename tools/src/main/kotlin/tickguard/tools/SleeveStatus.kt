package tickguard.tools

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import tickguard.stream.Decimal
import tickguard.trading.TestSleeves
import tickguard.trading.attribute
import tickguard.trading.position
import java.time.LocalDate

/**
 * What each test sleeve holds and is worth, from the order ledger.
 *
 *   ./gradlew :tools:sleeveStatus
 *
 * Holdings are marked at the last stored daily close. Reads only; runs beside the service.
 */
suspend fun main() {
    val store = openStore(environment())
    try {
        val orders = store.ordersSince(TestSleeves.START)
        val owned = attribute(orders, TestSleeves.ALL, store.orderTags(), TestSleeves.PERSONAL, TestSleeves.START)
        val unassigned = orders.filter { order -> owned.values.none { order in it } }
        for (sleeve in TestSleeves.ALL) {
            val position = position(sleeve, owned[sleeve.id].orEmpty())
            val today = LocalDate.now()
            val prices =
                position.holdings.keys.associateWith { code ->
                    store.bars(code, today.minusDays(LOOKBACK_DAYS), today).lastOrNull()?.close
                }
            val marked =
                position.holdings.entries.fold(Decimal.ZERO) { total, (code, qty) ->
                    total +
                        qty * (prices[code] ?: Decimal.ZERO)
                }
            val value = position.cash + marked
            val change = (value / sleeve.capital - Decimal.ONE) * Decimal.HUNDRED
            println(
                "== ${sleeve.name} · 평가 \$${value.format(
                    2,
                )} (${change.format(
                    2,
                )}%) · 현금 \$${position.cash.format(2)} · 수수료 \$${position.feesPaid.format(2)} · 체결 ${position.fills}",
            )
            for ((code, qty) in position.holdings) {
                println(
                    "   ${code.padEnd(CODE_WIDTH)} ${qty.toPlainString()}주 @ ${prices[code]?.toPlainString() ?: "?"}",
                )
            }
        }
        if (unassigned.isNotEmpty()) {
            println("== 슬리브 밖 주문 ${unassigned.size}건 (개인 거래 또는 태그 필요)")
            unassigned.forEach { println("   ${it.symbol} ${it.side} ${it.status} ${it.orderId.take(ID_SHOWN)}…") }
        }
    } finally {
        withContext(NonCancellable) { store.close() }
    }
}

/** Far enough back to find the last close across a long weekend. */
private const val LOOKBACK_DAYS = 10L

/** Wide enough for a US ticker like BRK.B. */
private const val CODE_WIDTH = 6

/** Enough of an order id to recognise it, not the whole opaque string. */
private const val ID_SHOWN = 6
