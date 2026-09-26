package tickguard.time

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Seoul time, from the named zone rather than the host's.
 *
 * The host's zone is the wrong thing to trust: a container runs in UTC unless
 * told otherwise, so anything built on local time looks right on a laptop and
 * quietly shifts nine hours once deployed.
 */
val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

/** The US exchanges' zone, for the local date the US market calendar is asked by. */
val NEW_YORK: ZoneId = ZoneId.of("America/New_York")

/** Pinned to Seoul, so formatting cannot fall back to the host's zone. */
private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(SEOUL)

/** See [DATE]. */
private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(SEOUL)

/** `2026-09-23`. Market hours are published per KST business day. */
fun kstDate(at: Instant): String = DATE.format(at)

/** `09:14:00`. What an alert is stamped with, read by someone in Seoul. */
fun kstTime(at: Instant): String = TIME.format(at)

/** Midnight in Seoul on the day [at] falls in. Budgets reset here. */
fun kstDayStart(at: Instant): Instant =
    at
        .atZone(SEOUL)
        .toLocalDate()
        .atStartOfDay(SEOUL)
        .toInstant()
