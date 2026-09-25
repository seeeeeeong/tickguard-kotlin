package tickguard.orders

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import tickguard.rest.RateLimitGroup
import tickguard.rest.RequestSpec
import tickguard.rest.RestClient
import java.time.LocalDate

/** The largest page the orders API serves. */
private const val ORDER_PAGE = 100

/**
 * A page count past which the loop stops. Far beyond any day this account
 * trades; reaching it means the cursor is not advancing.
 */
private const val MAX_ORDER_PAGES = 50

data class FetchedOrders(
    val orders: List<Order>,
    /** Rows that would not decode, each with why. Reported, never silently skipped. */
    val unreadable: List<String>,
)

/**
 * Orders over REST, for the resync after a reconnect. `OPEN` comes whole and
 * ignores paging; `CLOSED` is paged and filtered by the KST date the order
 * was placed, from [from] on.
 */
suspend fun fetchOrders(
    rest: RestClient,
    status: String,
    from: LocalDate? = null,
): FetchedOrders {
    val orders = ArrayList<Order>()
    val unreadable = ArrayList<String>()
    var cursor: String? = null
    var pages = 0
    do {
        val body =
            rest.get(
                RequestSpec(
                    path = "/api/v1/orders",
                    group = RateLimitGroup.ORDER_HISTORY,
                    query =
                        mapOf(
                            "status" to status,
                            "from" to from?.toString(),
                            "limit" to ORDER_PAGE.toString(),
                            "cursor" to cursor,
                        ),
                    withAccount = true,
                ),
            )
        val page = parseOrders(body)
        orders += page.orders
        unreadable += page.unreadable
        cursor = page.nextCursor
        pages += 1
        val more = page.hasNext && cursor != null
    } while (status == "CLOSED" && more && pages < MAX_ORDER_PAGES)
    return FetchedOrders(orders, unreadable)
}

data class OrdersPage(
    val orders: List<Order>,
    val unreadable: List<String>,
    val nextCursor: String?,
    val hasNext: Boolean,
)

fun parseOrders(body: JsonElement?): OrdersPage {
    val result = (body as? JsonObject)?.get("result") as? JsonObject
    val rows = (result?.get("orders") as? JsonArray).orEmpty()
    val orders = ArrayList<Order>()
    val unreadable = ArrayList<String>()
    for (row in rows) {
        val json = row as? JsonObject
        if (json == null) {
            unreadable += "order is not an object"
            continue
        }
        try {
            orders += decodeOrder(json)
        } catch (failure: Unreadable) {
            unreadable += "${(json["orderId"] as? JsonPrimitive)?.content ?: "?"}: ${failure.message}"
        }
    }
    val next = result?.get("nextCursor") as? JsonPrimitive
    return OrdersPage(
        orders = orders,
        unreadable = unreadable,
        nextCursor = next?.takeIf { it.isString }?.content,
        hasNext = (result?.get("hasNext") as? JsonPrimitive)?.content == "true",
    )
}
