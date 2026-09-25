package tickguard.rules

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import tickguard.stream.Trade
import java.time.Instant
import java.time.InstantSource
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * Read once, at load, and written behind. The engine runs on every tick and
 * must not wait on a database to decide whether a signal is suppressed, so
 * what it knows lives in memory; the store only carries it across restarts.
 */
interface CooldownStore {
    /** Delivered fires at or after [since]. An undelivered one never told anyone, so it seeds nothing. */
    suspend fun firesSince(since: Instant): Map<String, Instant>

    /** Recorded when the rule fires, as not yet delivered. */
    suspend fun recordFire(
        key: String,
        signal: Signal,
    )

    /** The fire's alert reached someone. A newer fire under the same key is left as it is. */
    suspend fun markDelivered(
        key: String,
        firedAt: Instant,
    )

    suspend fun pruneFires(olderThan: Instant): Int
}

data class RuleEngineStats(
    val evaluated: Int,
    val fired: Int,
    val suppressed: Int,
    /** Conditions currently holding but not yet long enough to fire. */
    val pending: Int,
    val errors: Int,
    /** Live cooldown entries. Growth here means keys are too specific. */
    val trackedKeys: Int,
    /** Cooldowns given back because their alert was never delivered. */
    val released: Int = 0,
)

/**
 * Evaluates every rule against every tick and suppresses repeats.
 *
 * Two independent kinds of suppression, which Prometheus separates for the
 * same reason:
 *
 * - `holdFor` — the condition has to hold continuously before anything fires.
 *   This is what stops a blip becoming an alert. A price that touches a
 *   threshold for one print and bounces is the spread moving.
 * - `cooldown` — after firing, the same key stays quiet. This is what stops
 *   one event becoming hundreds of identical alerts, since a threshold rule
 *   holds true on every tick while the price sits on the wrong side.
 *
 * Both are keyed per rule *and* per dedupe key, so one noisy symbol cannot
 * silence a different one.
 *
 * A cooldown only means something once its alert went out. A fire is stored
 * as undelivered; delivery marks it, and only delivered fires survive a
 * restart. An abandoned delivery releases the cooldown, so the next
 * qualifying tick fires again rather than the rule staying quiet for an hour
 * about something nobody heard. Alertmanager's notification log records a
 * notification only once it was sent, for the same reason.
 *
 * Runs on the engine, like everything that touches this state. A rule that
 * blocks holds the engine, so rules are expected to be arithmetic.
 */
class RuleEngine(
    private val rules: List<Rule>,
    private val positions: () -> Map<String, Position> = { emptyMap() },
    /** Absent means rules see no history; span-based rules then never fire. */
    private val windows: WindowStore? = null,
    private val clock: InstantSource = InstantSource.system(),
    private val onSignal: (Signal) -> Unit,
    private val onRuleError: (Exception, String) -> Unit = { _, _ -> },
    /** A cooldown that failed to persist. Suppression in memory still holds. */
    private val onStoreError: (Exception) -> Unit = {},
    /**
     * Seeds and persists cooldowns. Without it a restart re-fires every rule
     * that still holds, so a deploy during a drawdown sends the same alert again.
     */
    private val cooldowns: CooldownStore? = null,
    /** Where writes to [cooldowns] run. The engine's own scope. */
    private val scope: CoroutineScope? = null,
) {
    private val lastFiredAt = LinkedHashMap<String, Instant>()

    /** When the condition for a key first held. Cleared the moment it stops. */
    private val pendingSince = LinkedHashMap<String, Instant>()
    private val longestCooldown: Duration = rules.maxOfOrNull { it.cooldown } ?: Duration.ZERO
    private var evaluated = 0
    private var fired = 0
    private var suppressed = 0
    private var pending = 0
    private var errors = 0
    private var released = 0

    /**
     * Seeds cooldowns from the store. Must finish before the first tick, or a
     * restart re-fires what it had already reported.
     */
    suspend fun load() {
        val seeded = cooldowns?.firesSince(clock.instant().minus(longestCooldown.toJavaDuration())).orEmpty()
        // A fire already made in this process is newer than anything stored.
        for ((key, firedAt) in seeded) lastFiredAt.putIfAbsent(key, firedAt)
    }

    @Suppress("TooGenericExceptionCaught") // A broken rule must not stop the others.
    fun evaluate(trade: Trade) {
        windows?.record(trade)

        val context =
            RuleContext(
                trade = trade,
                now = clock.instant(),
                position = positions()[trade.code],
                window = { span -> windows?.snapshot(trade.code, span) },
            )
        evaluated += 1

        for (rule in rules) {
            try {
                runRule(rule, context)
            } catch (failure: Exception) {
                errors += 1
                onRuleError(failure, rule.id)
            }
        }
    }

    fun stats() =
        RuleEngineStats(
            evaluated = evaluated,
            fired = fired,
            suppressed = suppressed,
            pending = pending,
            errors = errors,
            trackedKeys = (lastFiredAt.keys + pendingSince.keys).size,
            released = released,
        )

    private fun runRule(
        rule: Rule,
        context: RuleContext,
    ) {
        val key = "${rule.id} ${rule.dedupeKey(context)}"
        val outcome = rule.evaluate(context)

        if (outcome == null) {
            pendingSince -= key
            return
        }

        val firedAt = lastFiredAt[key]
        if (firedAt != null && elapsed(firedAt, context.now) < rule.cooldown.inWholeMilliseconds) {
            suppressed += 1
            return
        }

        if (!heldLongEnough(rule, key, context.now)) {
            pending += 1
            return
        }

        val signal = Signal(rule.id, outcome.code, outcome.title, outcome.detail, context.now, cooldownKey = key)
        pendingSince -= key
        lastFiredAt[key] = context.now
        persist { recordFire(key, signal) }
        fired += 1
        prune(context.now)
        onSignal(signal)
    }

    /** The fire's alert went out: only now does its cooldown outlive a restart. */
    fun delivered(
        key: String,
        firedAt: Instant,
    ) = persist { markDelivered(key, firedAt) }

    /**
     * The fire's alert never went out: give its cooldown back. A newer fire
     * under the same key keeps its own.
     */
    fun release(
        key: String,
        firedAt: Instant,
    ) {
        if (lastFiredAt[key] != firedAt) return
        lastFiredAt -= key
        released += 1
    }

    /**
     * True once the condition has held continuously for the rule's holdFor.
     *
     * The start time is cleared the moment the condition stops holding, so a
     * flickering condition never accumulates toward firing, which is the whole
     * point of requiring it to persist.
     */
    private fun heldLongEnough(
        rule: Rule,
        key: String,
        at: Instant,
    ): Boolean {
        if (rule.holdFor == Duration.ZERO) return true
        val since = pendingSince.getOrPut(key) { at }
        return elapsed(since, at) >= rule.holdFor.inWholeMilliseconds
    }

    /**
     * Entries older than the longest cooldown can never suppress anything, so
     * dropping them bounds a map that would otherwise grow with every symbol
     * ever seen. Cheap to do on a fire; pointless to do on every tick.
     */
    private fun prune(at: Instant) {
        lastFiredAt.entries.removeIf { elapsed(it.value, at) > longestCooldown.inWholeMilliseconds }
        persist { pruneFires(at.minus(longestCooldown.toJavaDuration())) }
    }

    @Suppress("TooGenericExceptionCaught") // A store failure of any kind must not reach the engine.
    private fun persist(write: suspend CooldownStore.() -> Unit) {
        val store = cooldowns ?: return
        val scope = scope ?: return
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

    private fun elapsed(
        from: Instant,
        to: Instant,
    ): Long = to.toEpochMilli() - from.toEpochMilli()
}
