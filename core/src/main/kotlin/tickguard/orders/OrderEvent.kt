package tickguard.orders

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import tickguard.gateway.ServerFrame
import tickguard.stream.Decimal
import tickguard.stream.NotANumberError
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * A change to one of the account's orders, as `personal:order` pushes it.
 *
 * Unlike quotes these are lossless within a connection and never redelivered
 * after one, so nothing here is allowed to be dropped quietly: a value this
 * code does not know (a new status Toss adds) is kept as the wire string
 * rather than refused, and a frame that cannot be read at all is counted and
 * reported by the caller, not skipped like an odd tick.
 *
 * `order` has the shape of `GET /api/v1/orders/{orderId}`, without
 * `execution.filledAt`, so the same type serves the resync after a reconnect.
 */
data class OrderEvent(
    /** `PENDING` · `PARTIAL_FILL` · `FILL` · `CANCELING` · `CANCELED` · `REPLACING` · `REPLACED` · `REJECTED` · … */
    val event: String,
    val accountSeq: String,
    val order: Order,
)

data class Order(
    val orderId: String,
    val symbol: String,
    /** `BUY` · `SELL` */
    val side: String,
    /** `LIMIT` · `MARKET` */
    val orderType: String,
    /** `DAY` · `CLS` · `OPG` */
    val timeInForce: String,
    /** `PENDING` · `PARTIAL_FILLED` · `FILLED` · `CANCELED` · `REJECTED` · `REPLACED` · … */
    val status: String,
    /** Absent for a market order. */
    val price: Decimal?,
    val quantity: Decimal,
    val orderAmount: Decimal?,
    /** `KRW` · `USD` */
    val currency: String,
    val orderedAt: Instant,
    val canceledAt: Instant?,
    val execution: Execution,
) {
    /** No further change can come: filled, cancelled, rejected or replaced. */
    val closed get() = status in CLOSED_STATUSES
}

data class Execution(
    val filledQuantity: Decimal,
    val averageFilledPrice: Decimal?,
    val filledAmount: Decimal?,
    val commission: Decimal?,
    val tax: Decimal?,
    val settlementDate: LocalDate?,
)

/** The statuses the orders API groups under `CLOSED`. */
val CLOSED_STATUSES = setOf("FILLED", "CANCELED", "REJECTED", "REPLACED")

sealed interface OrderDecode {
    data class Ok(
        val event: OrderEvent,
    ) : OrderDecode

    data class Failed(
        val topic: String,
        val reason: String,
    ) : OrderDecode
}

fun decodeOrderEvent(frame: ServerFrame.Message): OrderDecode {
    val data = frame.data as? JsonObject ?: return OrderDecode.Failed(frame.topic, "payload is not an object")
    return try {
        OrderDecode.Ok(
            OrderEvent(
                event = data.required("event"),
                accountSeq = data.required("accountSeq"),
                order = decodeOrder(data.obj("order")),
            ),
        )
    } catch (unreadable: Unreadable) {
        OrderDecode.Failed(frame.topic, unreadable.message.orEmpty())
    }
}

/** One order as the orders API and the event stream both describe it. Throws [Unreadable]. */
fun decodeOrder(json: JsonObject): Order {
    val execution = json.obj("execution")
    return Order(
        orderId = json.required("orderId"),
        symbol = json.required("symbol"),
        side = json.required("side"),
        orderType = json.required("orderType"),
        timeInForce = json.required("timeInForce"),
        status = json.required("status"),
        price = json.decimal("price"),
        quantity = json.requiredDecimal("quantity"),
        orderAmount = json.decimal("orderAmount"),
        currency = json.required("currency"),
        orderedAt = instant(json.required("orderedAt")),
        canceledAt = json.optional("canceledAt")?.let(::instant),
        execution =
            Execution(
                filledQuantity = execution.requiredDecimal("filledQuantity"),
                averageFilledPrice = execution.decimal("averageFilledPrice"),
                filledAmount = execution.decimal("filledAmount"),
                commission = execution.decimal("commission"),
                tax = execution.decimal("tax"),
                settlementDate = execution.optional("settlementDate")?.let(::date),
            ),
    )
}

/** Why a payload could not be read. Internal to decoding; callers see [OrderDecode.Failed]. */
class Unreadable(
    reason: String,
    cause: Throwable? = null,
) : IllegalArgumentException(reason, cause)

private fun JsonObject.obj(key: String): JsonObject =
    this[key] as? JsonObject ?: throw Unreadable("$key is not an object")

private fun JsonObject.requiredDecimal(key: String): Decimal = decimal(key) ?: throw Unreadable("$key is missing")

private fun JsonObject.optional(key: String): String? =
    when (val value = this[key]) {
        null, JsonNull -> null
        is JsonPrimitive -> if (value.isString) value.content else throw Unreadable("$key is not a string")
        else -> throw Unreadable("$key is not a string")
    }

private fun JsonObject.required(key: String): String = optional(key) ?: throw Unreadable("$key is missing")

private fun JsonObject.decimal(key: String): Decimal? =
    optional(key)?.let {
        try {
            Decimal.parse(it, key)
        } catch (notANumber: NotANumberError) {
            throw Unreadable(notANumber.message.orEmpty(), notANumber)
        }
    }

private fun instant(text: String): Instant =
    try {
        OffsetDateTime.parse(text).toInstant()
    } catch (unparsable: DateTimeParseException) {
        throw Unreadable("unparsable time $text", unparsable)
    }

private fun date(text: String): LocalDate =
    try {
        LocalDate.parse(text)
    } catch (unparsable: DateTimeParseException) {
        throw Unreadable("unparsable date $text", unparsable)
    }
