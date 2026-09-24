package tickguard.pipeline

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tickguard.store.TickRow
import java.io.IOException
import java.time.Instant

private fun row(n: Long) =
    TickRow("trade:us", "AMZN", "$n", "1", "USD", Instant.ofEpochMilli(n), Instant.ofEpochMilli(n))

class TickWriterTest {
    private val batches = mutableListOf<List<Long>>()

    private fun TestScope.writer(
        maxBatch: Int = 500,
        maxQueued: Int = 10_000,
        fail: () -> Boolean = { false },
    ) = TickWriter(
        write = { rows ->
            if (fail()) throw IOException("database is locked")
            batches += rows.map { it.tradedAt.toEpochMilli() }
        },
        scope = backgroundScope,
        maxBatch = maxBatch,
        maxQueued = maxQueued,
    )

    @Test
    fun `holds rows until the timer, then writes them as one batch`() =
        runTest {
            val writer = writer()
            writer.add(row(1))
            writer.add(row(2))
            runCurrent()
            assertThat(batches).isEmpty()

            advanceTimeBy(1_000)
            runCurrent()

            assertThat(batches).containsExactly(listOf(1L, 2L))
        }

    @Test
    fun `writes early once a full batch has gathered`() =
        runTest {
            val writer = writer(maxBatch = 3)
            for (n in 1L..3L) writer.add(row(n))
            runCurrent()

            assertThat(batches).containsExactly(listOf(1L, 2L, 3L))
        }

    @Test
    fun `drops the oldest rows past the bound, as the lossy stream would have`() =
        runTest {
            val writer = writer(maxQueued = 3)
            for (n in 1L..5L) writer.add(row(n))

            writer.flush()

            assertThat(batches).containsExactly(listOf(3L, 4L, 5L))
            assertThat(writer.stats()).isEqualTo(TickWriterStats(written = 3, failed = 0, dropped = 2, queued = 0))
        }

    @Test
    fun `counts a failed batch and carries on with the next`() =
        runTest {
            var fail = true
            val writer = writer(fail = { fail })
            writer.add(row(1))
            writer.flush()
            fail = false
            writer.add(row(2))
            writer.flush()

            assertThat(batches).containsExactly(listOf(2L))
            assertThat(writer.stats().written).isEqualTo(1)
            assertThat(writer.stats().failed).isEqualTo(1)
        }

    @Test
    fun `writes what is left on stop, so a clean shutdown loses nothing`() =
        runTest {
            val writer = writer()
            writer.add(row(1))

            writer.stop()

            assertThat(batches).containsExactly(listOf(1L))
        }

    @Test
    fun `shares one flush between concurrent callers`() =
        runTest {
            val writer = writer()
            writer.add(row(1))

            List(3) { async { writer.flush() } }.awaitAll()

            assertThat(batches).containsExactly(listOf(1L))
        }
}
