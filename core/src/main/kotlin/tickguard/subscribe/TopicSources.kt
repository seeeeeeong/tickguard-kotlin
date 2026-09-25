package tickguard.subscribe

/** Why a topic is watched. A topic stays subscribed while any source still wants it. */
enum class TopicSource {
    HOLDINGS,
    EXTRA,

    /** The account's own order events, wanted for as long as the process runs. */
    ACCOUNT,
}

/**
 * Counts who wants each topic, so one source letting go does not unsubscribe
 * a topic another still needs.
 *
 * Subscriptions are full-replace on the wire, and the coordinator keeps one
 * desired set, so it cannot know why a topic is in it. Without this, selling
 * a symbol that is also configured as an extra symbol removed it from the
 * set, and with it the SLA watch the extra symbol was configured for. The
 * same shape XChange uses for shared market-data subscriptions.
 *
 * Confined to the engine, like the coordinator it feeds.
 */
class TopicSources {
    private val sources = LinkedHashMap<Topic, MutableSet<TopicSource>>()

    /** Records [source]'s interest. Returns the topics nobody wanted before. */
    fun add(
        source: TopicSource,
        topics: List<Topic>,
    ): List<Topic> =
        topics.filter { topic ->
            val wanted = sources.getOrPut(topic) { mutableSetOf() }
            val fresh = wanted.isEmpty()
            wanted += source
            fresh
        }

    /** Drops [source]'s interest. Returns the topics nobody wants any more. */
    fun remove(
        source: TopicSource,
        topics: List<Topic>,
    ): List<Topic> =
        topics.filter { topic ->
            val wanted = sources[topic] ?: return@filter false
            wanted -= source
            if (wanted.isEmpty()) sources -= topic
            wanted.isEmpty()
        }
}
