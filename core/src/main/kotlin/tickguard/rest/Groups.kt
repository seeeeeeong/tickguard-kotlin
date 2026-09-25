package tickguard.rest

import tickguard.time.SEOUL
import java.time.Instant

/**
 * Rate limits are per client x API group, not per endpoint, so two different
 * calls can exhaust each other. Modelling the group is the only way to stay
 * under a limit you cannot see from a single call site.
 *
 * Numbers from the integration guide. They are documented as adjustable
 * without notice, so `X-RateLimit-Remaining` on the response is the authority
 * and these are the starting assumption. An enum, so an endpoint cannot name a
 * group the limiter does not know.
 */
enum class RateLimitGroup(
    val perSecond: Int,
    /**
     * Some groups are cut during the opening auction, the busiest ten minutes
     * of the day. Applying the normal limit then is how a client discovers the
     * peak rule by being throttled.
     */
    val peak: Peak? = null,
) {
    AUTH(5),
    ACCOUNT(1),
    ASSET(5),
    STOCK(5),
    STOCK_TRADING_TREND(10),
    MARKET_INFO(3),
    MARKET_DATA(15),
    MARKET_DATA_CHART(20),
    RANKING(5),
    ORDER_INFO(6, Peak(fromMinute = OPENING_AUCTION_FROM, toMinute = OPENING_AUCTION_TO, perSecond = 3)),

    /** Reading orders back. */
    ORDER_HISTORY(5),

    /** Placing orders, from the execution module only. Modifying and conditional orders have no group here. */
    ORDER(10),
}

data class Peak(
    /** Minutes past midnight in Seoul. */
    val fromMinute: Int,
    val toMinute: Int,
    val perSecond: Int,
)

/** 09:00 KST, in minutes past midnight: the KRX opening auction begins. */
private const val OPENING_AUCTION_FROM = 9 * 60

/** 09:10 KST: the opening auction's ten minutes are over. */
private const val OPENING_AUCTION_TO = 9 * 60 + 10

/** The group's limit at [at], cut during its peak window if it has one. */
fun limitAt(
    group: RateLimitGroup,
    at: Instant,
): Int {
    val peak = group.peak ?: return group.perSecond
    val local = at.atZone(SEOUL)
    val minuteOfDay = local.hour * 60 + local.minute
    return if (minuteOfDay >= peak.fromMinute && minuteOfDay < peak.toMinute) peak.perSecond else group.perSecond
}
