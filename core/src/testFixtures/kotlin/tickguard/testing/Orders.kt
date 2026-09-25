package tickguard.testing

import tickguard.orders.Execution
import tickguard.orders.Order
import java.time.Instant
import java.time.LocalDate

/** An order as the orders API describes it, with only what a test cares about spelled out. */
fun order(
    orderId: String = "o1",
    status: String = "FILLED",
    filled: String = "10",
    quantity: String = "10",
    symbol: String = "AAPL",
    price: String? = "100.5",
    orderedAt: Instant = Instant.parse("2026-09-25T00:30:00Z"),
) = Order(
    orderId = orderId,
    symbol = symbol,
    side = "BUY",
    orderType = if (price == null) "MARKET" else "LIMIT",
    timeInForce = "DAY",
    status = status,
    price = price?.let(::decimal),
    quantity = decimal(quantity),
    orderAmount = null,
    currency = "USD",
    orderedAt = orderedAt,
    canceledAt = if (status == "CANCELED") orderedAt.plusSeconds(60) else null,
    execution =
        Execution(
            filledQuantity = decimal(filled),
            averageFilledPrice = if (filled == "0") null else decimal("100"),
            filledAmount = if (filled == "0") null else decimal(filled) * decimal("100"),
            commission = if (filled == "0") null else decimal("1.23"),
            tax = if (filled == "0") null else decimal("0"),
            settlementDate = if (filled == "0") null else LocalDate.parse("2026-09-29"),
        ),
)
