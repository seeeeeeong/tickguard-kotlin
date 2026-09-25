package tickguard.notify

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import tickguard.rules.Signal
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * A notification is already formatted. Channels deliver text; deciding what
 * the text says belongs above them, with whatever knows whether this is one
 * signal or eight grouped together.
 */
data class Notification(
    /** For logs and metrics, not for display. */
    val key: String,
    val text: String,
    /** What the text was made from, so its delivery can be reported back to where they came from. */
    val signals: List<Signal> = emptyList(),
)

interface Channel {
    val name: String

    suspend fun send(notification: Notification)
}

/**
 * A channel's refusal that says whether trying again can help. Anything else
 * a channel throws — a timeout, a reset connection — is taken as transient.
 */
interface DeliveryFailure {
    /** False when the same request will be refused again: a revoked webhook, a malformed message. */
    val retryable: Boolean

    /** How long the channel asked to be left alone, when it said. */
    val retryAfter: Duration?
}

data class NotifierStats(
    val queued: Int,
    val delivered: Int,
    val retried: Int,
    val abandoned: Int,
    val pending: Int,
)

/**
 * Delivers notifications, and is allowed to fail without taking anything with it.
 *
 * A notifier sits at the end of the pipeline, so its failures must not
 * propagate backwards: Slack being down is not a reason to stop reading
 * quotes. Every send is therefore attempted, retried, and then given up on
 * with a count rather than an exception.
 *
 * Only what can succeed later is retried. A refusal the channel marks as
 * permanent is given up on at once, and a channel that asks for time with
 * Retry-After gets it: retrying a rate limit sooner only extends it. The
 * policy Alertmanager's retry stage applies to its receivers.
 *
 * Delivery is launched rather than awaited. The rules run on the engine, and
 * awaiting an HTTP round trip there would hold everything behind it.
 */
class Notifier(
    private val channels: List<Channel>,
    /** Where deliveries run. Channels do their own I/O off it. */
    private val scope: CoroutineScope,
    /** Total attempts per channel, including the first. */
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val baseDelay: Duration = DEFAULT_BASE_DELAY,
    private val onDelivered: (Notification, String) -> Unit = { _, _ -> },
    private val onGaveUp: (Notification, String, Exception) -> Unit = { _, _, _ -> },
    /**
     * Once per notification, when every channel has finished: true if all
     * delivered it. One channel giving up counts as not delivered, since the
     * channel that gave up may be the only one anybody reads.
     */
    private val onSettled: (Notification, delivered: Boolean) -> Unit = { _, _ -> },
) {
    private val queued = AtomicInteger()
    private val delivered = AtomicInteger()
    private val retried = AtomicInteger()
    private val abandoned = AtomicInteger()
    private val inFlight = ConcurrentHashMap.newKeySet<Job>()

    /** Returns immediately. Delivery happens off the caller's stack. */
    fun notify(notification: Notification) {
        queued.incrementAndGet()
        if (channels.isEmpty()) {
            onSettled(notification, false)
            return
        }
        val remaining = AtomicInteger(channels.size)
        val allDelivered = AtomicBoolean(true)
        for (channel in channels) {
            track(
                scope.launch {
                    if (!deliver(channel, notification)) allDelivered.set(false)
                    if (remaining.decrementAndGet() == 0) onSettled(notification, allDelivered.get())
                },
            )
        }
    }

    /** Waits for in-flight deliveries, including any queued while waiting. For shutdown and tests. */
    suspend fun drain() {
        while (inFlight.isNotEmpty()) inFlight.toList().joinAll()
    }

    fun stats() =
        NotifierStats(
            queued = queued.get(),
            delivered = delivered.get(),
            retried = retried.get(),
            abandoned = abandoned.get(),
            pending = inFlight.size,
        )

    /** True when delivered; false once given up on. */
    private suspend fun deliver(
        channel: Channel,
        notification: Notification,
    ): Boolean {
        var attempt = 1
        while (true) {
            val failure = attemptSend(channel, notification) ?: break
            val refusal = failure as? DeliveryFailure
            if (attempt >= maxAttempts || refusal?.retryable == false) {
                abandoned.incrementAndGet()
                onGaveUp(notification, channel.name, failure)
                return false
            }
            retried.incrementAndGet()
            val backoff = baseDelay * (1 shl (attempt - 1))
            delay(maxOf(backoff, minOf(refusal?.retryAfter ?: Duration.ZERO, MAX_RETRY_AFTER)))
            attempt += 1
        }
        delivered.incrementAndGet()
        onDelivered(notification, channel.name)
        return true
    }

    /** Returns the failure rather than throwing, so the loop stays flat. */
    @Suppress("TooGenericExceptionCaught") // A channel may fail in any way; every way is retried the same.
    private suspend fun attemptSend(
        channel: Channel,
        notification: Notification,
    ): Exception? =
        try {
            channel.send(notification)
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failure
        }

    private fun track(job: Job) {
        inFlight += job
        job.invokeOnCompletion { inFlight -= job }
    }

    private companion object {
        /** Three tries rides out a blip without hammering a channel that is down. */
        const val DEFAULT_MAX_ATTEMPTS = 3

        /**
         * The longest Retry-After honoured. A channel asking for more is down
         * for longer than an alert stays worth delivering late.
         */
        val MAX_RETRY_AFTER = 1.minutes

        /** Doubled per retry: 500ms, then 1s. */
        val DEFAULT_BASE_DELAY = 500.milliseconds
    }
}
