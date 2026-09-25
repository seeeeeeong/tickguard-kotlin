package tickguard.runner

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tickguard.orders.Execution
import tickguard.orders.Order
import tickguard.stream.Decimal
import java.time.Instant

class ControlPageTest {
    private val now = Instant.parse("2026-09-25T13:45:00Z")

    private fun order(
        id: String,
        symbol: String,
    ) = Order(
        orderId = id,
        symbol = symbol,
        side = "BUY",
        orderType = "MARKET",
        timeInForce = "DAY",
        status = "FILLED",
        price = null,
        quantity = Decimal.parse("0.046", "quantity"),
        orderAmount = Decimal.parse("30.68", "amount"),
        currency = "USD",
        orderedAt = now,
        canceledAt = null,
        execution =
            Execution(
                Decimal.parse("0.046", "filled"),
                Decimal.parse("663.2", "price"),
                null,
                null,
                null,
                null,
            ),
    )

    private fun view(
        windowEnd: Instant?,
        orders: List<Order> = emptyList(),
        tags: Map<String, String> = emptyMap(),
    ) = ControlView("on · A:DRY_RUN", false, now, windowEnd, null, emptyList(), null, orders, tags)

    @Test
    fun `says whether the order window is open and until when, in Seoul time`() {
        assertThat(renderControl(view(Instant.parse("2026-09-25T19:00:00Z")))).contains("열림 · 09-26 04:00 KST 까지")
        assertThat(renderControl(view(null))).contains("닫힘")
    }

    @Test
    fun `lists orders with their sleeve, a person's own untagged, and escapes what the broker sent`() {
        val page =
            renderControl(
                view(null, listOf(order("o-1", "SPY"), order("o-2", "<b>X</b>")), mapOf("o-1" to "A")),
            )

        assertThat(
            page,
        ).contains("<td>A</td><td>BUY</td><td>SPY</td><td>\$30.68</td><td>FILLED</td><td>0.046 @ 663.2</td>")
        assertThat(page).contains("<td>개인</td>").contains("&lt;b&gt;X&lt;/b&gt;").doesNotContain("<b>X</b>")
    }
}
