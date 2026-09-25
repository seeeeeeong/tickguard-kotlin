package tickguard.tools

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import tickguard.news.NewsItem
import tickguard.news.NewsKey
import tickguard.news.NewsSourceName
import tickguard.rules.Signal
import tickguard.store.TickRow
import tickguard.store.postgres.PostgresStore
import tickguard.store.sqlite.SqliteStore
import tickguard.verdict.Direction
import tickguard.verdict.Verdict
import java.nio.file.Files
import java.sql.DriverManager
import java.time.Instant

class MigrateToPostgresTest {
    private fun at(ms: Long) = Instant.ofEpochMilli(ms)

    private val story = NewsItem(NewsSourceName.GOOGLE_NEWS, "g1", "AAPL", "Apple sued", "CNBC", "https://x", at(5_000))
    private val failing = story.copy(id = "g2", title = "Apple again")

    @Test
    fun `copies every table so that the Postgres store reads back what the SQLite one held`() =
        runTest {
            val path = Files.createTempDirectory("tickguard-").resolve("source.db").toString()
            SqliteStore.open(path).apply {
                recordFire("drawdown-7pct AAPL:1", Signal("drawdown-7pct", "AAPL", "t", "d", at(9_000)))
                markDelivered("drawdown-7pct AAPL:1", at(9_000))
                // Never delivered: must not suppress after the move either.
                recordFire("rapid AAPL", Signal("rapid", "AAPL", "t", "d", at(9_500)))
                recordRejection("trade:us:NOPE", "stock-not-found", at(100))
                recordTicks(
                    listOf(
                        TickRow("trade:us", "AAPL", "339.20", "0.0000001", "USD", at(1_000), at(1_050)),
                        // Identical, one second: two trades, kept in arrival order.
                        TickRow("trade:us", "AAPL", "339.21", "5", "USD", at(1_000), at(1_060)),
                        TickRow("trade:us", "AAPL", "339.21", "5", "USD", at(1_000), at(1_060)),
                    ),
                )
                recordNews(story, at(6_000))
                recordNews(failing, at(6_100))
                recordVerdict(story.key(), Verdict(true, Direction.DOWN, 0.72, "제소"), "deepseek-flash", at(7_000))
                recordVerdictFailure(failing.key(), "timeout", 2, at(8_000))
                recordModelCall(at(7_000))
                close()
            }

            val schema = "migrated"
            val target = PostgresStore.open(postgres.jdbcUrl, postgres.username, postgres.password, schema)
            openReadOnly(mapOf("TICKGUARD_DB" to path)).use { sqlite -> copyInto(sqlite, target) }

            assertThat(target.firesSince(at(0))).containsOnlyKeys("drawdown-7pct AAPL:1")
            assertThat(target.rejectedTopics()).containsExactly("trade:us:NOPE")
            assertThat(target.ticksBetween("AAPL", at(0), at(2_000)).map { it.price to it.receivedAt })
                .containsExactly("339.20" to at(1_050), "339.21" to at(1_060), "339.21" to at(1_060))
            assertThat(target.ticksBetween("AAPL", at(0), at(2_000)).first().volume).isEqualTo("0.0000001")
            assertThat(target.newsFor("AAPL", at(0)).map { it.seenAt }).containsExactlyInAnyOrder(at(6_000), at(6_100))
            assertThat(target.verdictFor(story.key())!!.verdict).isEqualTo(Verdict(true, Direction.DOWN, 0.72, "제소"))
            // The failed story keeps its attempts, so the retry limit still counts them.
            assertThat(target.pendingVerdicts(at(0), at(8_000), 10).map { it.news.item.id to it.attempts })
                .containsExactly("g2" to 2)
            assertThat(target.modelCallsSince(at(0))).isEqualTo(1)
            target.close()

            val counts =
                compareCounts(
                    openReadOnly(mapOf("TICKGUARD_DB" to path)).use { it.counts() },
                    DriverManager
                        .getConnection(
                            postgres.jdbcUrl,
                            postgres.username,
                            postgres.password,
                        ).use { it.counts(schema = schema) },
                )
            assertThat(counts.matches).isTrue()
        }

    @Test
    fun `copies a file only the original wrote, whose fires have no delivered column`() =
        runTest {
            val path = Files.createTempDirectory("tickguard-").resolve("ts.db").toString()
            // The original's schema for the two tables this concerns; the rest are created empty.
            DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
                SqliteStore.open(path).close()
                db.createStatement().use {
                    it.execute("DROP TABLE fires")
                    it.execute(
                        "CREATE TABLE fires (key TEXT PRIMARY KEY, rule_id TEXT NOT NULL, " +
                            "code TEXT NOT NULL, fired_at INTEGER NOT NULL)",
                    )
                    it.execute("INSERT INTO fires VALUES ('drawdown-7pct AAPL', 'drawdown-7pct', 'AAPL', 9000)")
                }
            }

            val target = PostgresStore.open(postgres.jdbcUrl, postgres.username, postgres.password, "from_ts")
            openReadOnly(mapOf("TICKGUARD_DB" to path)).use { sqlite -> copyInto(sqlite, target) }

            assertThat(target.firesSince(at(0))).containsEntry("drawdown-7pct AAPL", at(9_000))
            target.close()
        }

    @Test
    fun `reports a table whose counts differ`() {
        val same = TABLES.associateWith { 1 }

        val report = compareCounts(same, same + ("ticks" to 0))

        assertThat(report.matches).isFalse()
        assertThat(report.text).contains("ticks").contains("✖")
    }

    private fun NewsItem.key() = NewsKey(source, id, code)

    private companion object {
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:16-alpine").apply { start() }
    }
}
