package tickguard.pipeline

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.time.InstantSource
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Drains the inbox on the engine in batches, yielding between them.
 *
 * In the original, draining and reading the socket shared one thread, so an
 * undrained batch starved the socket — the stall that gets the order channel
 * closed — and each batch carried a time budget to prevent it. Here the socket
 * is read on its own thread and only ever enqueues, so that stall cannot come
 * from here and the budget is gone. What remains is fairness on the engine:
 * yielding between batches lets its timers and scheduled work run during a burst.
 *
 * What is measured instead is how long an item waited between the socket and
 * its handler. That is the number the two-second deadline is about.
 */
class Pump<T>(
    private val inbox: Inbox<T>,
    private val handle: (T) -> Unit,
    private val clock: InstantSource = InstantSource.system(),
    /** Items per batch between yields. */
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    private val onHandlerError: (Exception, T) -> Unit = { _, _ -> },
) {
    private val handled = AtomicLong()
    private val maxBatchMs = AtomicLong()
    private val maxWaitMs = AtomicLong()
    private var job: Job? = null

    /** Starts draining in [scope], which must be the engine. */
    fun start(scope: CoroutineScope) {
        job =
            scope.launch {
                while (true) {
                    inbox.awaitWork()
                    drain()
                }
            }
    }

    fun stop() {
        job?.cancel()
    }

    fun handled(): Long = handled.get()

    /** Longest a single batch held the engine. */
    fun maxBatch(): Duration = maxBatchMs.get().milliseconds

    /** Longest an item waited between the socket and its handler. */
    fun maxWait(): Duration = maxWaitMs.get().milliseconds

    private suspend fun drain() {
        while (true) {
            val batch = inbox.take(batchSize)
            if (batch.isEmpty()) return

            val startedAt = clock.millis()
            for (queued in batch) {
                maxWaitMs.accumulateAndGet(startedAt - queued.at, ::maxOf)
                handleOne(queued.item)
            }
            maxBatchMs.accumulateAndGet(clock.millis() - startedAt, ::maxOf)
            yield()
        }
    }

    /** One bad item must not abandon the batch, nor stop the drain every other item needs. */
    @Suppress("TooGenericExceptionCaught") // Any failure of one item is isolated to that item.
    private fun handleOne(item: T) {
        try {
            handle(item)
            handled.incrementAndGet()
        } catch (failure: Exception) {
            onHandlerError(failure, item)
        }
    }

    private companion object {
        /** Enough to amortise a wake-up, few enough that a burst does not hold the engine. */
        const val DEFAULT_BATCH_SIZE = 256
    }
}
