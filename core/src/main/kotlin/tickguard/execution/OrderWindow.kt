package tickguard.execution

import tickguard.sla.MarketHours
import java.time.Instant
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

/** The opening minutes' quotes are wide; a market order sent then pays for it. */
private val AFTER_OPEN = 10.minutes.toJavaDuration()

/** Amount and fractional orders are accepted only until an hour before the regular close. */
private val BEFORE_CLOSE = 1.hours.toJavaDuration()

/**
 * Whether [now] falls in a regular session's order window: ten minutes after
 * its open to an hour before its close. Read from the exchange calendar, so a
 * holiday, an early close or a daylight-saving shift moves the window with it.
 * No calendar, no window.
 */
fun inOrderWindow(
    hours: MarketHours?,
    now: Instant,
): Boolean = orderWindowEnd(hours, now) != null

/** When the order window [now] falls in closes, or null outside one. */
fun orderWindowEnd(
    hours: MarketHours?,
    now: Instant,
): Instant? =
    hours
        ?.sessions
        .orEmpty()
        .filter { it.name.endsWith("regularMarket") }
        .firstOrNull { !now.isBefore(it.from.plus(AFTER_OPEN)) && !now.isAfter(it.to.minus(BEFORE_CLOSE)) }
        ?.to
        ?.minus(BEFORE_CLOSE)

/** The calendar's order window, opening to closing, or null without a regular session. */
fun orderWindow(hours: MarketHours?): ClosedRange<Instant>? =
    hours
        ?.sessions
        .orEmpty()
        .firstOrNull { it.name.endsWith("regularMarket") }
        ?.let { it.from.plus(AFTER_OPEN)..it.to.minus(BEFORE_CLOSE) }
