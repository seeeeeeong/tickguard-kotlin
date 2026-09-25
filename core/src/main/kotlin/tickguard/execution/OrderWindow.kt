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
): Boolean =
    hours?.sessions.orEmpty().any { session ->
        session.name.endsWith("regularMarket") &&
            !now.isBefore(session.from.plus(AFTER_OPEN)) &&
            !now.isAfter(session.to.minus(BEFORE_CLOSE))
    }
