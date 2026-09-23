package tickguard.testing

import tickguard.stream.Decimal
import tickguard.stream.Trade
import java.time.Instant

/** A trade as the rules see it, with only what a test cares about spelled out. */
fun tick(
    code: String,
    price: String,
    volume: String = "1",
    at: Instant = Instant.parse("2026-09-23T00:00:00Z"),
    type: String = "trade:kr",
    currency: String = "KRW",
) = Trade(
    type = type,
    code = code,
    price = Decimal.parse(price, "price"),
    volume = Decimal.parse(volume, "volume"),
    at = at,
    currency = currency,
)

fun decimal(text: String) = Decimal.parse(text, "test")
