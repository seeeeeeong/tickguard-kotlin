package tickguard.runner

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tickguard.orders.OrderSource
import tickguard.store.sqlite.SqliteStore
import tickguard.testing.order
import java.nio.file.Files
import java.time.Instant
import java.time.InstantSource
import kotlin.time.Duration.Companion.milliseconds

class FillsTest {
    private val store = SqliteStore.open(Files.createTempDirectory("tickguard-").resolve("app.db").toString())
    private val now = Instant.parse("2026-09-28T14:00:00Z")

    @Test
    fun `answers yes once every sale has filled, and no on a refusal or a sale still open`() =
        runTest {
            store.recordOrder(order("filled", status = "FILLED"), "FILL", OrderSource.STREAM, now)
            store.recordOrder(order("rejected", status = "REJECTED", filled = "0"), "REJECTED", OrderSource.STREAM, now)
            store.recordOrder(order("open", status = "PENDING", filled = "0"), "PENDING", OrderSource.STREAM, now)
            val clock = InstantSource.system()
            val quick = 50.milliseconds

            assertThat(awaitFilled(store, clock, listOf("filled"), quick, 10.milliseconds)).isTrue()
            assertThat(awaitFilled(store, clock, listOf("filled", "rejected"), quick, 10.milliseconds)).isFalse()
            assertThat(awaitFilled(store, clock, listOf("filled", "open"), quick, 10.milliseconds)).isFalse()
        }
}
