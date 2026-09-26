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

/**
 * The date of the last regular session that has closed by [now], from the
 * calendar's session names ("2026-11-02 regularMarket"), or null when none
 * has: the close a proposal must be priced on.
 */
fun lastSession(
    hours: MarketHours?,
    now: Instant,
): java.time.LocalDate? =
    hours
        ?.sessions
        .orEmpty()
        .filter { it.name.endsWith("regularMarket") && !it.to.isAfter(now) }
        .maxByOrNull { it.to }
        ?.name
        ?.substringBefore(" ")
        ?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }

/**
 * The date of the earliest regular session the calendar lists, whether or
 * not it has closed. After midnight in Seoul the calendar lists only the US
 * session still running and the next one, so the last closed session is
 * the last trading day before this.
 */
fun firstSession(hours: MarketHours?): java.time.LocalDate? =
    hours
        ?.sessions
        .orEmpty()
        .filter { it.name.endsWith("regularMarket") }
        .mapNotNull { runCatching { java.time.LocalDate.parse(it.name.substringBefore(" ")) }.getOrNull() }
        .minOrNull()

/** The calendar's order window, opening to closing, or null without a regular session. */
fun orderWindow(hours: MarketHours?): ClosedRange<Instant>? =
    hours
        ?.sessions
        .orEmpty()
        .firstOrNull { it.name.endsWith("regularMarket") }
        ?.let { it.from.plus(AFTER_OPEN)..it.to.minus(BEFORE_CLOSE) }
