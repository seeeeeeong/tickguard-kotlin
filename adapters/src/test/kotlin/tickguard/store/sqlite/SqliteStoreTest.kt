package tickguard.store.sqlite

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tickguard.store.TickRow
import tickguard.testing.StoreContract
import tickguard.testing.failureOf
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant

class SqliteStoreTest : StoreContract() {
    override fun backing(): Backing {
        val directory = Files.createTempDirectory("tickguard-")
        val path = directory.resolve("test.db").toString()
        return object : Backing {
            override suspend fun open() = SqliteStore.open(path)

            override fun dispose() = directory.toFile().deleteRecursively().let {}
        }
    }

    private fun tick(volume: String) =
        TickRow("trade:us", "AAPL", "340.39", volume, "USD", Instant.ofEpochMilli(1_000), Instant.ofEpochMilli(1_050))

    @Test
    fun `writes a batch whole or not at all`() =
        runTest {
            val path = Files.createTempDirectory("tickguard-").resolve("test.db").toString()
            val store = SqliteStore.open(path)
            // A row the database refuses, after one it accepts. The schema accepts
            // every row Kotlin's types allow, so the refusal is made by hand.
            DriverManager.getConnection("jdbc:sqlite:$path").use {
                it.createStatement().execute(
                    "CREATE TRIGGER refuse BEFORE INSERT ON ticks WHEN NEW.volume = '2' " +
                        "BEGIN SELECT RAISE(ABORT, 'refused'); END",
                )
            }

            assertThat(failureOf { store.recordTicks(listOf(tick("1"), tick("2"))) }).hasMessageContaining("refused")
            assertThat(store.ticksBetween("AAPL", Instant.EPOCH, Instant.ofEpochMilli(2_000))).isEmpty()
            store.close()
        }

    @Test
    fun `reads a database the original service wrote, so it can be cut over on the same file`() =
        runTest {
            // Written by the TypeScript original's own openSqliteStore, with made-up data.
            val copy = Files.createTempDirectory("tickguard-").resolve("ts-made.db")
            Files.copy(Path.of(checkNotNull(javaClass.getResource("/ts-made.db")).toURI()), copy)
            val store = SqliteStore.open(copy.toString())

            assertThat(
                store.firesSince(Instant.EPOCH),
            ).containsEntry("drawdown-7pct AAPL", Instant.ofEpochMilli(1_758_600_000_000))
            assertThat(store.rejectedTopics()).containsExactly("trade:us:NOPE")
            assertThat(store.ticksBetween("AAPL", Instant.EPOCH, Instant.ofEpochMilli(Long.MAX_VALUE)).map { it.price })
                .containsExactly("339.24", "339.2")
            assertThat(
                store
                    .newsFor("AMZN", Instant.EPOCH)
                    .single()
                    .item.title,
            ).isEqualTo("FTC sues Amazon")
            assertThat(store.modelCallsSince(Instant.EPOCH)).isEqualTo(1)
            store.close()
        }
}
