package tickguard.store.postgres

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import tickguard.store.TickRow
import tickguard.testing.StoreContract
import tickguard.testing.failureOf
import java.sql.DriverManager
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class PostgresStoreTest : StoreContract() {
    override fun backing(): Backing {
        val schema = "t${schemas.incrementAndGet()}"
        return object : Backing {
            override suspend fun open() =
                PostgresStore.open(postgres.jdbcUrl, postgres.username, postgres.password, schema)

            override fun dispose() = sql("DROP SCHEMA IF EXISTS $schema CASCADE")
        }
    }

    private fun tick(volume: String) =
        TickRow("trade:us", "AAPL", "340.39", volume, "USD", Instant.ofEpochMilli(1_000), Instant.ofEpochMilli(1_050))

    @Test
    fun `writes a batch whole or not at all`() =
        runTest {
            val backing = backing()
            val store = backing.open()
            // The schema accepts every row Kotlin's types allow, so the refusal is made by hand.
            sql(
                """
                CREATE FUNCTION t${schemas.get()}.refuse() RETURNS trigger AS
                ${'$'}${'$'} BEGIN IF NEW.volume = 2 THEN RAISE EXCEPTION 'refused'; END IF; RETURN NEW; END ${'$'}${'$'}
                LANGUAGE plpgsql;
                CREATE TRIGGER refuse BEFORE INSERT ON t${schemas.get()}.ticks
                FOR EACH ROW EXECUTE FUNCTION t${schemas.get()}.refuse();
                """.trimIndent(),
            )

            assertThat(failureOf { store.recordTicks(listOf(tick("1"), tick("2"))) }).hasMessageContaining("refused")
            assertThat(store.ticksBetween("AAPL", Instant.EPOCH, Instant.ofEpochMilli(2_000))).isEmpty()
            store.close()
            backing.dispose()
        }

    @Test
    fun `keeps a price's scale, which the text column kept by storing the string`() =
        runTest {
            val backing = backing()
            val store = backing.open()

            store.recordTicks(listOf(TickRow("trade:us", "AAPL", "339.20", "1", "USD", Instant.EPOCH, Instant.EPOCH)))

            assertThat(
                store.ticksBetween("AAPL", Instant.EPOCH, Instant.ofEpochMilli(1)).single().price,
            ).isEqualTo("339.20")
            store.close()
            backing.dispose()
        }

    @Test
    fun `migrates an existing schema without touching its rows`() =
        runTest {
            val backing = backing()
            backing.open().apply {
                recordTicks(listOf(tick("1")))
                close()
            }

            val reopened = backing.open()

            assertThat(reopened.ticksBetween("AAPL", Instant.EPOCH, Instant.ofEpochMilli(2_000))).hasSize(1)
            reopened.close()
            backing.dispose()
        }

    private companion object {
        /** One server for the class; each test gets a schema of its own. */
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:16-alpine").apply { start() }

        val schemas = AtomicInteger()

        fun sql(statement: String) {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use {
                it.createStatement().use { s -> s.execute(statement) }
            }
        }
    }
}
