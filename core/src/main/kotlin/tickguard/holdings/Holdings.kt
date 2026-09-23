package tickguard.holdings

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import tickguard.rest.RateLimitGroup
import tickguard.rest.RequestSpec
import tickguard.rest.RestClient
import tickguard.rules.Position
import tickguard.stream.Decimal
import tickguard.stream.NotANumberError
import java.time.Instant
import java.time.InstantSource

data class HoldingsSnapshot(
    val positions: Map<String, Position>,
    /** `trade:kr` or `trade:us` per symbol, so subscriptions know the channel. */
    val markets: Map<String, String>,
    val fetchedAt: Instant,
)

data class HoldingsStats(
    val refreshes: Int,
    val failures: Int,
    val skipped: Int,
)

/**
 * Keeps the set of held positions current, and lets subscriptions follow it.
 *
 * What is worth watching is not a list someone types once. It is whatever is
 * held right now, so buying something should start watching it and selling it
 * should stop — without an edit. Polling is the only way to see that: the
 * order channel reports fills, but a position also changes through dividends,
 * splits and transfers that never appear as an order here.
 *
 * Field shapes were read off a live response rather than assumed. The list is
 * under `result.items`, quantities and prices are strings, and `marketCountry`
 * is what distinguishes a KR symbol from a US one.
 *
 * Confined to the engine: refreshes, [current] and the change callback all run there.
 */
class HoldingsStore(
    private val rest: RestClient,
    private val clock: InstantSource = InstantSource.system(),
    private val onChange: (added: List<String>, removed: List<String>) -> Unit = { _, _ -> },
    private val onError: (Exception) -> Unit = {},
) {
    private var snapshot = HoldingsSnapshot(emptyMap(), emptyMap(), Instant.EPOCH)
    private var refreshes = 0
    private var failures = 0
    private var skipped = 0

    @Suppress("TooGenericExceptionCaught") // Any failure keeps the last known positions.
    suspend fun refresh(): HoldingsSnapshot {
        try {
            val body = rest.get(RequestSpec("/api/v1/holdings", RateLimitGroup.ASSET, withAccount = true))
            val parsed = parseHoldings(body)
            refreshes += 1
            skipped += parsed.skipped

            val previous = snapshot
            snapshot = HoldingsSnapshot(parsed.positions, parsed.markets, clock.instant())
            report(previous, snapshot)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // A failed refresh keeps the last known positions rather than
            // emptying them: pretending nothing is held would silence every
            // position rule until the next poll succeeds.
            failures += 1
            onError(failure)
        }
        return snapshot
    }

    fun current(): HoldingsSnapshot = snapshot

    fun stats() = HoldingsStats(refreshes, failures, skipped)

    private fun report(
        previous: HoldingsSnapshot,
        next: HoldingsSnapshot,
    ) {
        val added = next.positions.keys.filter { it !in previous.positions }
        val removed = previous.positions.keys.filter { it !in next.positions }
        if (added.isNotEmpty() || removed.isNotEmpty()) onChange(added, removed)
    }
}

data class ParsedHoldings(
    val positions: Map<String, Position>,
    val markets: Map<String, String>,
    val skipped: Int,
)

fun parseHoldings(body: JsonElement?): ParsedHoldings {
    val positions = LinkedHashMap<String, Position>()
    val markets = LinkedHashMap<String, String>()
    var skipped = 0

    val items = (((body as? JsonObject)?.get("result") as? JsonObject)?.get("items") as? JsonArray).orEmpty()
    for (item in items) {
        val parsed = parseItem(item)
        if (parsed == null) {
            // One unreadable row must not discard the rest of the portfolio.
            skipped += 1
        } else {
            positions[parsed.first.code] = parsed.first
            markets[parsed.first.code] = parsed.second
        }
    }
    return ParsedHoldings(positions, markets, skipped)
}

private fun parseItem(item: JsonElement): Pair<Position, String>? {
    val fields = item as? JsonObject
    val symbol = fields?.string("symbol")?.takeIf { it.isNotEmpty() }
    val quantity = fields?.string("quantity")
    val averagePrice = fields?.string("averagePurchasePrice")
    if (symbol == null || quantity == null || averagePrice == null) return null

    return try {
        val position =
            Position(
                code = symbol,
                averagePrice = Decimal.parse(averagePrice, "averagePurchasePrice"),
                quantity = Decimal.parse(quantity, "quantity"),
            )
        position to if (fields.string("marketCountry") == "KR") "trade:kr" else "trade:us"
    } catch (_: NotANumberError) {
        null
    }
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
