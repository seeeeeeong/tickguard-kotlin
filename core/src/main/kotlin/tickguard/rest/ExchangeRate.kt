package tickguard.rest

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import tickguard.stream.Decimal
import java.io.IOException

/**
 * Toss's won-per-dollar display rate, refreshed every minute. For showing a
 * proposal in won only: the rate an order is converted at is Toss's own, and
 * can differ.
 */
suspend fun fetchUsdKrw(rest: RestClient): Decimal {
    val body =
        rest.get(
            RequestSpec(
                path = "/api/v1/exchange-rate",
                group = RateLimitGroup.MARKET_INFO,
                query = mapOf("baseCurrency" to "USD", "quoteCurrency" to "KRW"),
            ),
        )
    val rate = ((body as? JsonObject)?.get("result") as? JsonObject)?.get("rate") as? JsonPrimitive
    return rate?.takeIf { it.isString }?.let { Decimal.parse(it.content, "rate") }
        ?: throw IOException("exchange rate response had no rate")
}
