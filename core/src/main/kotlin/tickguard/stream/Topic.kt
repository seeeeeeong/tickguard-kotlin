package tickguard.stream

/**
 * A message topic is the subscription type with the code appended:
 * `trade:us:AAPL`, `orderbook:kr:005930`, `personal:order:3`.
 *
 * The type itself contains colons, so splitting on the first one is wrong and
 * splitting into three parts is wrong too. Only the last segment is the code;
 * everything before it is the type, whatever shape that type has. That keeps
 * this correct if Toss adds a channel with a different number of segments.
 */
data class ParsedTopic(
    /** `trade:us`, `orderbook:kr`, `personal:order`. */
    val type: String,
    /** A symbol for quote channels, an accountSeq for `personal:order`. */
    val code: String,
)

fun parseTopic(topic: String): ParsedTopic? {
    // Every real type already contains a colon (`trade:us`, `personal:order`),
    // so a topic with only one is malformed rather than a two-segment channel.
    // Without this check `trade:us` would parse as code `us`.
    if (topic.split(":").size < MIN_SEGMENTS) return null

    val lastColon = topic.lastIndexOf(':')
    if (lastColon == topic.length - 1) return null

    return ParsedTopic(type = topic.substring(0, lastColon), code = topic.substring(lastColon + 1))
}

/** Channel, market or kind, and code: the fewest segments a real topic has. */
private const val MIN_SEGMENTS = 3

/** `trade` and `orderbook` carry a market; `personal:order` does not. */
fun channelOf(type: String): String = type.substringBefore(':')
