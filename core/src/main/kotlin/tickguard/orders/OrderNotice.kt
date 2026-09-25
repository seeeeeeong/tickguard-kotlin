package tickguard.orders

import tickguard.stream.Decimal

data class OrderNotice(
    val title: String,
    val detail: String,
)

/** What is worth a message. Accepted, pending and replaced orders are not: the next change says more. */
private val OUTCOMES =
    mapOf(
        "FILLED" to "체결",
        "PARTIAL_FILLED" to "부분 체결",
        "CANCELED" to "취소",
        "REJECTED" to "거부",
        "CANCEL_REJECTED" to "취소 거부",
        "REPLACE_REJECTED" to "정정 거부",
    )

/** The side in the words the Toss app uses. */
private val SIDES = mapOf("BUY" to "매수", "SELL" to "매도")

/**
 * The message for an order's new state, or null when the state is not news.
 * `AAPL 매수 체결 10/10주 @ 100` over `지정가 100.5 USD · 수수료 1.23 · 세금 0`.
 */
fun orderNotice(
    order: Order,
    source: OrderSource,
): OrderNotice? {
    val outcome = OUTCOMES[order.status] ?: return null
    val filled = order.execution.filledQuantity
    val at =
        order.execution.averageFilledPrice
            ?.takeIf { filled.signum() > 0 }
            ?.let { " @ ${it.toPlainString()}" }
            .orEmpty()
    val title =
        "${order.symbol} ${SIDES[order.side] ?: order.side} $outcome ${filled.toPlainString()}/" +
            "${order.quantity.toPlainString()}주$at"

    val parts =
        listOfNotNull(
            order.price?.let { "지정가 ${it.toPlainString()} ${order.currency}" } ?: "시장가 ${order.currency}",
            order.execution.commission.shown("수수료"),
            order.execution.tax.shown("세금"),
        )
    val gap = if (source == OrderSource.RESYNC) "\n(연결이 끊긴 사이의 변화, 재연결 후 확인)" else ""
    return OrderNotice(title, parts.joinToString(" · ") + gap)
}

private fun Decimal?.shown(label: String): String? = this?.let { "$label ${it.toPlainString()}" }
