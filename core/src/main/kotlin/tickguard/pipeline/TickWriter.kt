package tickguard.pipeline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tickguard.store.TickRow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class TickWriterStats(
    val written: Int,
    val failed: Int,
    val dropped: Int,
    val queued: Int,
)

/**
 * Collects ticks and writes them in batches.
 *
 * The original first wrote one row per tick and measured why that was fine:
 * ~13 µs a row into SQLite in-process, about 1% of the loop at a thousand
 * ticks a second. A database across a network turns each row into a round
 * trip, and a round trip per tick at that rate is most of a second per
 * second. So rows gather here and go out together, every second or every
 * 500 rows.
 *
 * The cost that brings: a crash loses up to a second of ticks. Quotes are
 * lossy by design, the history is for replay, and a clean shutdown flushes
 * what is held, so that is the right trade.
 *
 * The queue is bounded. A store that stalls for minutes must not grow memory
 * without limit; past the bound the oldest rows go first, which is what a
 * lossy stream would have lost anyway. A failed batch is counted and dropped,
 * and writing carries on with the next.
 *
 * [scope] must be the engine: the queue is confined to it, as every other
 * piece of domain state is.
 */
class TickWriter(
    private val write: suspend (List<TickRow>) -> Unit,
    private val scope: CoroutineScope,
    flushEvery: Duration = 1.seconds,
    private val maxBatch: Int = DEFAULT_MAX_BATCH,
    private val maxQueued: Int = DEFAULT_MAX_QUEUED,
    private val onError: (Exception) -> Unit = {},
) {
    private val queue = ArrayDeque<TickRow>()
    private var flushing: Deferred<Unit>? = null
    private var written = 0
    private var failed = 0
    private var dropped = 0

    private val timer: Job =
        scope.launch {
            while (true) {
                delay(flushEvery)
                flush()
            }
        }

    fun add(row: TickRow) {
        queue.addLast(row)
        val excess = queue.size - maxQueued
        if (excess > 0) {
            repeat(excess) { queue.removeFirst() }
            dropped += excess
        }
        if (queue.size >= maxBatch) startFlush()
    }

    /** Writes everything queued. Concurrent calls share one flush. */
    suspend fun flush() = startFlush().await()

    /** Stops the timer and writes what is left. */
    suspend fun stop() {
        timer.cancel()
        flushing?.await()
        flush()
    }

    fun stats() = TickWriterStats(written, failed, dropped, queue.size)

    private fun startFlush(): Deferred<Unit> =
        flushing ?: scope
            .async {
                try {
                    drain()
                } finally {
                    flushing = null
                }
            }.also { flushing = it }

    @Suppress("TooGenericExceptionCaught") // A failed batch is counted and dropped, whatever failed.
    private suspend fun drain() {
        while (queue.isNotEmpty()) {
            val batch = List(minOf(maxBatch, queue.size)) { queue.removeFirst() }
            try {
                write(batch)
                written += batch.size
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failed += batch.size
                onError(failure)
            }
        }
    }

    private companion object {
        /** Five hundred rows go in one statement batch; a second's worth on a busy day. */
        const val DEFAULT_MAX_BATCH = 500

        /** Ten thousand rows: some minutes of a stalled store, a few megabytes of memory. */
        const val DEFAULT_MAX_QUEUED = 10_000
    }
}
