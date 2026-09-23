package tickguard.notify

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * A notification is already formatted. Channels deliver text; deciding what
 * the text says belongs above them, with whatever knows whether this is one
 * signal or eight grouped together.
 */
data class Notification(
    /** For logs and metrics, not for display. */
    val key: String,
    val text: String,
)

interface Channel {
    val name: String

    suspend fun send(notification: Notification)
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
) {
    private val queued = AtomicInteger()
    private val delivered = AtomicInteger()
    private val retried = AtomicInteger()
    private val abandoned = AtomicInteger()
    private val inFlight = ConcurrentHashMap.newKeySet<Job>()

    /** Returns immediately. Delivery happens off the caller's stack. */
    fun notify(notification: Notification) {
        queued.incrementAndGet()
        for (channel in channels) track(scope.launch { deliver(channel, notification) })
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

    private suspend fun deliver(
        channel: Channel,
        notification: Notification,
    ) {
        for (attempt in 1..maxAttempts) {
            val failure = attemptSend(channel, notification)
            if (failure == null) {
                delivered.incrementAndGet()
                onDelivered(notification, channel.name)
                return
            }
            if (attempt == maxAttempts) {
                abandoned.incrementAndGet()
                onGaveUp(notification, channel.name, failure)
                return
            }
            retried.incrementAndGet()
            delay(baseDelay * (1 shl (attempt - 1)))
        }
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

        /** Doubled per retry: 500ms, then 1s. */
        val DEFAULT_BASE_DELAY = 500.milliseconds
    }
}
