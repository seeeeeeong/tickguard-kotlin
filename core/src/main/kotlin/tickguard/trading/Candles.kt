package tickguard.trading

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import tickguard.rest.RateLimitGroup
import tickguard.rest.RequestSpec
import tickguard.rest.RestClient
import tickguard.stream.Decimal
import tickguard.stream.NotANumberError
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** The most bars the candles API returns in one call. */
private const val CANDLES_PER_CALL = 200

/** Pages past which a backfill stops: 200 bars a page is far beyond any listing's history. */
private const val MAX_CANDLE_PAGES = 100

data class FetchedBars(
    /** Oldest first, one per day. */
    val bars: List<Bar>,
    /** Candles that would not decode, each with why. */
    val unreadable: List<String>,
)

/**
 * Daily bars for [code] back to [from], adjusted for splits and dividends as
 * the API does by default.
 *
 * Pages run newest to oldest, and each page's `nextBefore` is inclusive: the
 * next page starts with the bar the last one ended on. So bars are keyed by
 * day, and a day seen twice is kept once.
 */
suspend fun fetchDailyBars(
    rest: RestClient,
    code: String,
    from: LocalDate,
): FetchedBars {
    val byDay = java.util.TreeMap<LocalDate, Bar>()
    val unreadable = ArrayList<String>()
    var before: String? = null
    var pages = 0
    while (pages < MAX_CANDLE_PAGES) {
        val page =
            parseCandles(
                code,
                rest.get(
                    RequestSpec(
                        path = "/api/v1/candles",
                        group = RateLimitGroup.MARKET_DATA_CHART,
                        query =
                            mapOf(
                                "symbol" to code,
                                "interval" to "1d",
                                "count" to CANDLES_PER_CALL.toString(),
                                "before" to before,
                            ),
                    ),
                ),
            )
        pages += 1
        unreadable += page.unreadable
        val fresh = page.bars.filter { it.day !in byDay }
        fresh.forEach { byDay[it.day] = it }
        val lastPage = page.nextBefore == null || fresh.isEmpty()
        val reachedStart = page.bars.minOfOrNull { it.day }?.let { it <= from } ?: true
        if (lastPage || reachedStart) break
        before = page.nextBefore
    }
    return FetchedBars(byDay.tailMap(from, true).values.toList(), unreadable)
}

data class CandlePage(
    val bars: List<Bar>,
    val unreadable: List<String>,
    val nextBefore: String?,
)

/**
 * A daily candle is stamped at midnight in the market's own offset
 * (`2026-03-25T00:00:00+09:00`), so its date is read in that offset, never in
 * the host's zone.
 */
fun parseCandles(
    code: String,
    body: JsonElement?,
): CandlePage {
    val result = (body as? JsonObject)?.get("result") as? JsonObject
    val bars = ArrayList<Bar>()
    val unreadable = ArrayList<String>()
    for (row in (result?.get("candles") as? JsonArray).orEmpty()) {
        val json = row as? JsonObject
        val timestamp = json?.text("timestamp")
        try {
            bars +=
                Bar(
                    code = code,
                    day = OffsetDateTime.parse(checkNotNull(timestamp) { "timestamp is missing" }).toLocalDate(),
                    open = json.decimal("openPrice"),
                    high = json.decimal("highPrice"),
                    low = json.decimal("lowPrice"),
                    close = json.decimal("closePrice"),
                    volume = json.decimal("volume"),
                )
        } catch (failure: IllegalStateException) {
            unreadable += "$code ${timestamp ?: "?"}: ${failure.message}"
        } catch (failure: DateTimeParseException) {
            unreadable += "$code $timestamp: ${failure.message}"
        } catch (failure: NotANumberError) {
            unreadable += "$code $timestamp: ${failure.message}"
        }
    }
    return CandlePage(bars, unreadable, result?.text("nextBefore"))
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.decimal(key: String): Decimal = Decimal.parse(checkNotNull(text(key)) { "$key is missing" }, key)
