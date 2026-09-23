package tickguard.rest

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import tickguard.stream.Decimal
import tickguard.stream.NotANumberError

/** Prices can be fetched in bulk; one call per symbol would spend the group. */
private const val MAX_SYMBOLS_PER_CALL = 50

/**
 * Last prices over REST, in bulk. Shared by the reconciler, which compares
 * them with the stream, and the fallback, which stands in for the stream
 * while it is down.
 */
suspend fun fetchPrices(
    rest: RestClient,
    symbols: List<String>,
): Map<String, Decimal> {
    val prices = LinkedHashMap<String, Decimal>()
    for (batch in symbols.chunked(MAX_SYMBOLS_PER_CALL)) {
        val body =
            rest.get(
                RequestSpec(
                    path = "/api/v1/prices",
                    group = RateLimitGroup.MARKET_DATA,
                    query = mapOf("symbols" to batch.joinToString(",")),
                ),
            )
        prices.putAll(parsePrices(body))
    }
    return prices
}

/** One unreadable row is skipped; it must not discard the rest of the comparison. */
fun parsePrices(body: JsonElement?): Map<String, Decimal> {
    val rows = ((body as? JsonObject)?.get("result") as? JsonArray).orEmpty()
    return rows.mapNotNull(::toPrice).toMap(LinkedHashMap())
}

private fun toPrice(row: JsonElement): Pair<String, Decimal>? {
    val fields = row as? JsonObject
    val symbol = fields?.string("symbol")
    val lastPrice = fields?.string("lastPrice")
    if (symbol == null || lastPrice == null) return null
    return try {
        symbol to Decimal.parse(lastPrice, "lastPrice")
    } catch (_: NotANumberError) {
        null
    }
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
