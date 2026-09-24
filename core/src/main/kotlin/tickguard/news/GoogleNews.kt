package tickguard.news

import tickguard.text.JS_SPACES
import tickguard.text.jsTrim
import java.security.MessageDigest
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.HexFormat

/**
 * Headlines from Google News search RSS, one query per symbol.
 *
 * The feed's own terms allow it "within a personal feed reader for personal,
 * non-commercial use", which is what this is: one person watching their own
 * positions.
 *
 * Parsed with the original's regular expressions rather than an XML parser.
 * The feed is machine-generated with a fixed shape, and the regular expressions
 * are what decided each stored headline's text, and so its id; a parser that
 * decoded one entity differently would re-file old stories as new after the
 * cut-over.
 *
 * The id is a hash of the headline, not the feed's guid. Syndicated copies of
 * one story carry the same headline under different guids, and one story
 * should reach the judge once.
 */
fun parseGoogleNews(
    xml: String,
    code: String,
): List<NewsItem> =
    ITEM
        .findAll(xml)
        .mapNotNull { match ->
            val block = match.groupValues[1]
            val rawTitle = tag(block, "title")
            val url = tag(block, "link")
            val publishedAt = tag(block, "pubDate")?.let(::rfc1123)
            if (rawTitle == null || url == null || publishedAt == null) return@mapNotNull null

            val publisher = tag(block, "source").orEmpty()
            // "Headline - Publisher": the publisher is kept in its own column.
            val suffix = " - $publisher"
            val title =
                if (publisher.isNotEmpty() &&
                    rawTitle.endsWith(suffix)
                ) {
                    rawTitle.dropLast(suffix.length)
                } else {
                    rawTitle
                }

            NewsItem(NewsSourceName.GOOGLE_NEWS, headlineId(title), code, title, publisher, url, publishedAt)
        }.toList()

/** The headline's own identity: lowercased, whitespace collapsed, hashed. */
fun headlineId(title: String): String {
    val normalized = title.lowercase().replace(JS_SPACES, " ").jsTrim()
    val digest = MessageDigest.getInstance("SHA-1").digest(normalized.toByteArray())
    return HexFormat.of().formatHex(digest).take(HEADLINE_ID_LENGTH)
}

/** Sixteen hex digits: enough to keep a symbol's day of headlines apart. */
private const val HEADLINE_ID_LENGTH = 16

/** One `<item>` block, lazily, as `/<item>([\s\S]*?)<\/item>/g`. */
private val ITEM = Regex("<item>([\\s\\S]*?)</item>")

/** A title wrapped whole in CDATA, which is taken as written: no entity in it is decoded. */
private val CDATA = Regex("^<!\\[CDATA\\[([\\s\\S]*)]]>\\z")

private fun tag(
    block: String,
    name: String,
): String? {
    val content = Regex("<$name(?:\\s[^>]*)?>([\\s\\S]*?)</$name>").find(block)?.groupValues?.get(1) ?: return null
    val cdata = CDATA.find(content)?.groupValues?.get(1)
    return (cdata ?: decodeEntities(content)).jsTrim()
}

/** `&#39;`: a character by its decimal code point. */
private val DECIMAL_ENTITY = Regex("&#(\\d+);")

/** `&#x27;`: a character by its hexadecimal code point, either case, as the original's `/gi`. */
private val HEX_ENTITY = Regex("&#x([0-9a-f]+);", RegexOption.IGNORE_CASE)

private fun decodeEntities(text: String): String =
    text
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace(DECIMAL_ENTITY) { String(Character.toChars(it.groupValues[1].toInt())) }
        .replace(HEX_ENTITY) { String(Character.toChars(it.groupValues[1].toInt(HEX))) }
        // Last, so "&amp;lt;" becomes "&lt;" and not "<".
        .replace("&amp;", "&")

/** Base of a `&#x…;` entity. */
private const val HEX = 16

/** RSS dates are RFC 1123 (`Tue, 22 Sep 2026 17:10:22 GMT`); one that will not parse is skipped, not invented. */
private fun rfc1123(text: String): Instant? =
    try {
        ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
    } catch (_: DateTimeParseException) {
        null
    }
