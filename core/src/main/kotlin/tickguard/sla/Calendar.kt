package tickguard.sla

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import tickguard.rest.RateLimitGroup
import tickguard.rest.RequestSpec
import tickguard.rest.RestClient
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

enum class Market { KR, US }

data class Session(
    val name: String,
    val from: Instant,
    val to: Instant,
)

data class MarketHours(
    val market: Market,
    val date: String,
    val sessions: List<Session>,
)

/**
 * When each market is actually trading.
 *
 * Without this an SLA watcher reports an outage every evening, because no
 * ticks and a closed market look identical from inside the process. An alert
 * that fires every night is one nobody reads, which costs more than having no
 * alert at all.
 *
 * Two shapes, read off live responses rather than assumed: KR nests its
 * sessions under `integrated` (KRX and NXT combined), US does not and adds a
 * `dayMarket`. Sessions are compared as instants, never as times of day.
 *
 * Both `today` and `previousBusinessDay` are loaded, which live testing
 * proved necessary rather than thorough. US regular hours run 22:30 to 05:00
 * the next day in KST, so at 00:26 the session actually trading belongs to
 * the *previous* business day. Reading only `today` reports the market closed
 * for the six and a half hours it is most obviously open.
 *
 * Loaded and read on the engine.
 */
class Calendar(
    private val rest: RestClient,
) {
    private val cache = LinkedHashMap<Market, MarketHours>()

    /** Fetches and caches the market's sessions. */
    suspend fun load(market: Market): MarketHours? {
        val body = rest.get(RequestSpec("/api/v1/market-calendar/$market", RateLimitGroup.MARKET_INFO))
        return parseCalendar(market, body)?.also { cache[market] = it }
    }

    /** False when hours are unknown, so an outage is never reported on a guess. */
    fun isOpen(
        market: Market,
        at: Instant,
    ): Boolean = cache[market]?.sessions?.any { at >= it.from && at < it.to } ?: false

    fun hours(market: Market): MarketHours? = cache[market]

    /**
     * The market's sessions as of its own local [date], not cached: the
     * given day and the business day before it. Asked by the exchange's
     * date, the day before is always a session that has closed, which the
     * Seoul-dated calendar stops naming after midnight in Seoul.
     */
    suspend fun on(
        market: Market,
        date: java.time.LocalDate,
    ): MarketHours? {
        val body =
            rest.get(
                RequestSpec(
                    "/api/v1/market-calendar/$market",
                    RateLimitGroup.MARKET_INFO,
                    mapOf("date" to date.toString()),
                ),
            )
        return parseCalendar(market, body)
    }
}

/** The session kinds either market can list, in the order they run. */
private val SESSION_KEYS = listOf("preMarket", "regularMarket", "afterMarket", "dayMarket")

fun parseCalendar(
    market: Market,
    body: JsonElement?,
): MarketHours? {
    val result = body.pick("result")
    val today = result.pick("today") ?: return null

    val sessions = (sessionsOf(result.pick("previousBusinessDay")) + sessionsOf(today)).sortedBy { it.from }
    if (sessions.isEmpty()) return null

    return MarketHours(market, today.string("date").orEmpty(), sessions)
}

private fun sessionsOf(day: JsonObject?): List<Session> {
    if (day == null) return emptyList()
    // KR wraps its sessions in `integrated`; US lists them directly.
    val container = day.pick("integrated") ?: day
    val prefix = day.string("date")?.let { "$it " }.orEmpty()
    return SESSION_KEYS.mapNotNull { key -> toSession("$prefix$key", container.pick(key)) }
}

private fun toSession(
    name: String,
    value: JsonObject?,
): Session? {
    val from = value?.string("startTime")?.let(::instantOf)
    val to = value?.string("endTime")?.let(::instantOf)
    if (from == null || to == null || to <= from) return null
    return Session(name, from, to)
}

private fun instantOf(text: String): Instant? =
    try {
        OffsetDateTime.parse(text).toInstant()
    } catch (_: DateTimeParseException) {
        null
    }

private fun JsonElement?.pick(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
