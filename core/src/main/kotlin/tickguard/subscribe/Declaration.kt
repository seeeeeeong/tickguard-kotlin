package tickguard.subscribe

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * A declaration is the whole subscription set, not a change to it. The server
 * has no subscribe/unsubscribe action: whatever array arrives replaces what
 * came before, and anything left out is silently unsubscribed.
 *
 * That makes the client the owner of the set, and makes building the array a
 * pure function of it — which is the only part of this worth testing directly.
 */
data class Topic(
    /** Channel and market, e.g. `trade:us`, `orderbook:kr`, `personal:order`. */
    val type: String,
    /** A symbol for quote channels, an accountSeq for `personal:order`. */
    val code: String,
)

/** The full key the server uses in acks and message topics: `trade:us:AAPL`. */
fun topicKey(topic: Topic): String = "${topic.type}:${topic.code}"

/** Per connection, counting channel x code combinations. */
const val MAX_TOPICS_PER_CONNECTION = 100

/** Declarations are limited to 5/s, with no Retry-After to guide a retry. */
const val MAX_DECLARATIONS_PER_SECOND = 5

/**
 * Groups and sorts so the same set always produces the same bytes. Stable
 * output makes a declaration diffable in a log, which is the only way to see
 * what changed between two of them.
 *
 * An empty set sends `[]`, the documented way to clear everything. The id
 * element is dropped in that case because an id-only array is not a shape the
 * spec describes, and a guess here would fail as a silent no-op.
 *
 * Sorting is by UTF-16 code unit, as the original's `sort()` was. Its types
 * were sorted with `localeCompare`, which agrees for the lowercase ASCII and
 * colons every channel type is made of.
 */
fun buildDeclaration(
    topics: Iterable<Topic>,
    id: String,
): String {
    val codesByType = sortedMapOf<String, MutableSet<String>>()
    for (topic in topics) codesByType.getOrPut(topic.type) { sortedSetOf() }.add(topic.code)

    if (codesByType.isEmpty()) return "[]"

    return buildJsonArray {
        addJsonObject { put("id", id) }
        for ((type, codes) in codesByType) {
            addJsonObject {
                put("type", type)
                putJsonArray("codes") { codes.forEach { add(JsonPrimitive(it)) } }
            }
        }
    }.toString()
}

fun countTopics(topics: Iterable<Topic>): Int = topics.map(::topicKey).toSet().size
