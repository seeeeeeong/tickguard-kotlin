package tickguard.subscribe

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tickguard.gateway.ServerFrame
import java.time.Instant
import java.time.InstantSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Long enough for a burst of adds to land in one declaration. */
val COALESCE = 50.milliseconds

/** 5/s allows 200ms; the margin covers scheduler drift. */
val MIN_DECLARATION_INTERVAL = 1.seconds / MAX_DECLARATIONS_PER_SECOND + 50.milliseconds

/** Rejections outlive the process: the server rejects them again every time. */
interface RejectionStore {
    suspend fun rejectedTopics(): List<String>

    suspend fun recordRejection(
        target: String,
        code: String,
        at: Instant,
    )

    suspend fun clearRejection(target: String)
}

data class CoordinatorSnapshot(
    val desired: List<String>,
    val rejected: List<String>,
    val declarations: Int,
)

/**
 * Owns the subscription set and decides when to declare it.
 *
 * Three constraints shape this:
 *
 * - Declarations are full-replace, so every change means re-sending everything.
 *   Callers therefore describe intent (add/remove) and never build an array.
 * - Declarations are capped at 5/s with no Retry-After. Changes are coalesced
 *   into one declaration per window, which also means ten adds in a loop cost
 *   one round trip rather than ten.
 * - Rejected topics are rejected again on every re-declaration. They are
 *   remembered and left out, or a bad symbol would repeat its failure on every
 *   reconnect for the life of the process.
 *
 * Confined to [scope], which must run one coroutine at a time: the engine. Its
 * timers are coroutines there too, so cancelling one and its firing can never
 * interleave. Writes to [rejections] are sent off and not awaited.
 */
class SubscriptionCoordinator(
    private val scope: CoroutineScope,
    private val clock: InstantSource = InstantSource.system(),
    private val rejections: RejectionStore? = null,
    /** A rejection that failed to persist. The set in memory still holds. */
    private val onStoreError: (Exception) -> Unit = {},
    private val capacity: Int = MAX_TOPICS_PER_CONNECTION,
    private val coalesce: Duration = COALESCE,
    private val minInterval: Duration = MIN_DECLARATION_INTERVAL,
) {
    private val desired = LinkedHashMap<String, Topic>()

    // Seeded from the store by load(), so a restart does not re-learn the
    // same failures.
    private val rejected = LinkedHashSet<String>()

    private var send: ((String) -> Unit)? = null
    private var pending: Job? = null
    private var lastDeclaredAt: Long? = null
    private var declarations = 0

    /** Bumped per connection so a late ack from a dead socket cannot be believed. */
    private var generation = 0

    /** Seeds known rejections from the store. Must finish before the first attach. */
    suspend fun load() {
        rejected += rejections?.rejectedTopics().orEmpty()
    }

    /**
     * Declares interest. A topic already known to be rejected stays excluded —
     * startup re-declares everything, so clearing here would re-try every bad
     * symbol on every restart and make remembering them pointless.
     */
    fun add(topics: List<Topic>) {
        val next = LinkedHashMap(desired)
        for (topic in topics) next[topicKey(topic)] = topic

        val size = countTopics(next.values)
        if (size > capacity) throw TopicCapacityError(size, capacity)

        desired.putAll(next)
        scheduleDeclaration()
    }

    /** Says to try a rejected topic again. The only thing that clears one. */
    fun retry(topics: List<Topic>) {
        for (topic in topics) {
            val key = topicKey(topic)
            desired[key] = topic
            rejected -= key
            persist { clearRejection(key) }
        }
        scheduleDeclaration()
    }

    fun remove(topics: List<Topic>) {
        for (topic in topics) {
            val key = topicKey(topic)
            desired -= key
            rejected -= key
            persist { clearRejection(key) }
        }
        scheduleDeclaration()
    }

    /** Call on every connection. Declares the full set, because a new socket has none. */
    fun attach(send: (String) -> Unit) {
        generation += 1
        this.send = send
        // A new socket holds no subscriptions, so this is not an optimisation to
        // skip when nothing changed — it is the only thing that makes it live.
        lastDeclaredAt = null
        scheduleDeclaration()
    }

    fun detach() {
        send = null
        pending?.cancel()
        pending = null
    }

    fun handleFrame(frame: ServerFrame) {
        if (frame !is ServerFrame.Subscriptions) return
        if (frame.id != null && !frame.id.startsWith("g$generation-")) return

        for (entry in frame.rejected) {
            rejected += entry.target
            persist { recordRejection(entry.target, entry.code, clock.instant()) }
        }
    }

    fun snapshot() =
        CoordinatorSnapshot(
            desired = desired.keys.sorted(),
            rejected = rejected.sorted(),
            declarations = declarations,
        )

    private fun declareNow() {
        pending = null
        val send = send ?: return

        declarations += 1
        lastDeclaredAt = clock.millis()
        val effective = desired.filterKeys { it !in rejected }.values
        send(buildDeclaration(effective, "g$generation-$declarations"))
    }

    private fun scheduleDeclaration() {
        if (send == null || pending != null) return

        val sinceLast = lastDeclaredAt?.let { (clock.millis() - it).milliseconds }
        val wait = if (sinceLast == null) coalesce else maxOf(coalesce, minInterval - sinceLast)
        pending =
            scope.launch {
                delay(wait)
                declareNow()
            }
    }

    @Suppress("TooGenericExceptionCaught") // A store failure of any kind must not reach the engine.
    private fun persist(write: suspend RejectionStore.() -> Unit) {
        val store = rejections ?: return
        scope.launch {
            try {
                store.write()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                onStoreError(failure)
            }
        }
    }
}
