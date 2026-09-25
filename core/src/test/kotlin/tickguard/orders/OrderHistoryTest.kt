package tickguard.orders

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tickguard.rest.RateLimitGroup
import tickguard.testing.StubRest
import java.time.LocalDate

class OrderHistoryTest {
    private fun row(id: String) =
        """{"orderId":"$id","symbol":"AAPL","side":"SELL","orderType":"MARKET","timeInForce":"DAY",""" +
            """"status":"FILLED",""" +
            """"price":null,"quantity":"2","orderAmount":null,"currency":"USD",""" +
            """"orderedAt":"2026-09-25T10:00:00+09:00",""" +
            """"canceledAt":null,"execution":{"filledQuantity":"2","averageFilledPrice":"220.5",""" +
            """"filledAmount":"441",""" +
            """"commission":"0.44","tax":"0","filledAt":"2026-09-25T10:00:01+09:00",""" +
            """"settlementDate":"2026-09-29"}}"""

    private fun page(
        vararg rows: String,
        next: String? = null,
    ) = """{"result":{"orders":[${rows.joinToString(",")}],""" +
        """"nextCursor":${next?.let { "\"$it\"" } ?: "null"},"hasNext":${next != null}}}"""

    @Test
    fun `follows the cursor through every closed page, as an account read`() =
        runTest {
            val rest = StubRest(page(row("a"), next = "c1"), page(row("b")))

            val fetched = fetchOrders(rest, "CLOSED", LocalDate.parse("2026-09-25"))

            assertThat(fetched.orders.map { it.orderId }).containsExactly("a", "b")
            assertThat(rest.asked).hasSize(2)
            assertThat(rest.asked.map { it.query["cursor"] }).containsExactly(null, "c1")
            assertThat(rest.asked.first().query).containsEntry("status", "CLOSED").containsEntry("from", "2026-09-25")
            assertThat(rest.asked.all { it.withAccount && it.group == RateLimitGroup.ORDER_HISTORY }).isTrue()
        }

    @Test
    fun `asks once for open orders, which come whole`() =
        runTest {
            // hasNext is ignored for OPEN: the API returns every working order at once.
            val rest = StubRest(page(row("a"), next = "c1"))

            fetchOrders(rest, "OPEN")

            assertThat(rest.asked).hasSize(1)
        }

    @Test
    fun `reports a row it cannot read, by its order id, and keeps the rest`() {
        val broken = row("bad").replace(""""quantity":"2"""", """"quantity":2""")

        val parsed =
            parseOrders(
                kotlinx.serialization.json.Json
                    .parseToJsonElement(page(row("ok"), broken)),
            )

        assertThat(parsed.orders.map { it.orderId }).containsExactly("ok")
        assertThat(parsed.unreadable).containsExactly("bad: quantity is not a string")
    }
}
