package tickguard.tools

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import tickguard.news.NewsItem
import tickguard.news.NewsKey
import tickguard.news.NewsSourceName
import tickguard.rules.Signal
import tickguard.store.Store
import tickguard.store.TickRow
import tickguard.store.postgres.PostgresStore
import tickguard.verdict.Direction
import tickguard.verdict.Verdict
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Instant
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Copies the service's SQLite database into Postgres, for the cut-over.
 *
 *   ./gradlew :tools:migrateToPostgres --args="data/tickguard.db"
 *
 * Reads TICKGUARD_PG_URL, TICKGUARD_PG_USER and TICKGUARD_PG_PASSWORD. Run it
 * with the service stopped: a tick written during the copy would be in one
 * database and not the other.
 *
 * Rows go in through the Postgres store's own writes, the ones the store
 * contract tests, so every conversion (epoch milliseconds to timestamptz,
 * price text to numeric, 0/1 to boolean) is the one the service uses. It
 * refuses a target that already has rows, because ticks carry no key that
 * would make a second run harmless, and it ends by comparing row counts table
 * by table.
 *
 * A judged verdict arrives with one attempt, whatever it took to get there:
 * the count only limits retries of failed stories, and those keep theirs.
 */
suspend fun main(args: Array<String>) {
    val env = environment()
    val source = args.getOrNull(0) ?: env["TICKGUARD_DB"] ?: "tickguard.db"
    val url = env["TICKGUARD_PG_URL"] ?: fail("TICKGUARD_PG_URL is not set.")
    val user = env["TICKGUARD_PG_USER"] ?: fail("TICKGUARD_PG_USER is not set.")
    val password = env["TICKGUARD_PG_PASSWORD"] ?: fail("TICKGUARD_PG_PASSWORD is not set.")

    openReadOnly(mapOf("TICKGUARD_DB" to source)).use { sqlite ->
        val target = PostgresStore.open(url, user, password)
        val postgres = DriverManager.getConnection(url, user, password)
        try {
            val existing = postgres.counts(schema = POSTGRES_SCHEMA).filterValues { it > 0 }
            if (existing.isNotEmpty()) fail("The target already has rows ($existing). Copy into an empty schema.")

            copyInto(sqlite, target) { table, rows -> println("  $table: $rows") }

            val report = compareCounts(sqlite.counts(), postgres.counts(schema = POSTGRES_SCHEMA))
            println(report.text)
            if (!report.matches) exitProcess(1)
        } finally {
            postgres.close()
            withContext(NonCancellable) { target.close() }
        }
    }
}

/** The tables both stores keep, in the order they are copied. */
val TABLES = listOf("fires", "rejections", "ticks", "news", "verdicts", "model_calls")

/** Where [PostgresStore.open] puts the tables by default. */
private const val POSTGRES_SCHEMA = "tickguard"

/** Ticks per transaction: large enough to be quick, small enough to report progress. */
private const val TICK_BATCH = 5_000

/** Copies every table from [sqlite] into [target]. [progress] hears each table's row count. */
suspend fun copyInto(
    sqlite: Connection,
    target: Store,
    progress: (table: String, rows: Int) -> Unit = { _, _ -> },
) {
    // A file only the original ever wrote has no delivered column. Its fires all
    // count as delivered, which is what the service's own migration assumes.
    val delivered = if ("delivered" in sqlite.columns("fires")) "delivered" else "1 AS delivered"
    val fires = sqlite.rows("SELECT key, rule_id, code, fired_at, $delivered FROM fires") { it.fire() }
    for (fire in fires) {
        target.recordFire(fire.key, fire.signal)
        if (fire.delivered) target.markDelivered(fire.key, fire.signal.firedAt)
    }
    progress("fires", fires.size)

    val rejections =
        sqlite.rows("SELECT target, code, rejected_at FROM rejections") {
            Triple(it.getString("target"), it.getString("code"), it.instant("rejected_at"))
        }
    for ((topic, code, at) in rejections) target.recordRejection(topic, code, at)
    progress("rejections", rejections.size)

    progress("ticks", copyTicks(sqlite, target))

    val news =
        sqlite.rows("SELECT source, id, code, title, publisher, url, published_at, seen_at FROM news") {
            it.newsItem() to it.instant("seen_at")
        }
    for ((item, seenAt) in news) target.recordNews(item, seenAt)
    progress("news", news.size)

    progress("verdicts", copyVerdicts(sqlite, target))

    val calls = sqlite.rows("SELECT at FROM model_calls") { it.instant("at") }
    for (at in calls) target.recordModelCall(at)
    progress("model_calls", calls.size)
}

/** In arrival order, which is how the service's rowid kept two trades in one second apart. */
private suspend fun copyTicks(
    sqlite: Connection,
    target: Store,
): Int {
    var copied = 0
    sqlite
        .prepareStatement(
            "SELECT type, code, price, volume, currency, traded_at, received_at FROM ticks ORDER BY rowid",
        ).use { statement ->
            statement.executeQuery().use { rows ->
                val batch = ArrayList<TickRow>(TICK_BATCH)
                while (rows.next()) {
                    batch +=
                        TickRow(
                            type = rows.getString("type"),
                            code = rows.getString("code"),
                            price = rows.getString("price"),
                            volume = rows.getString("volume"),
                            currency = rows.getString("currency"),
                            tradedAt = rows.instant("traded_at"),
                            receivedAt = rows.instant("received_at"),
                        )
                    if (batch.size == TICK_BATCH) {
                        target.recordTicks(batch)
                        copied += batch.size
                        batch.clear()
                    }
                }
                if (batch.isNotEmpty()) target.recordTicks(batch)
                copied += batch.size
            }
        }
    return copied
}

private suspend fun copyVerdicts(
    sqlite: Connection,
    target: Store,
): Int {
    val rows =
        sqlite.rows(
            """
            SELECT source, id, code, status, relevant, direction, impact, summary, model,
                   judged_at, attempts, retry_at, last_error FROM verdicts
            """.trimIndent(),
        ) { it.verdictRow() }
    for (row in rows) {
        when (row) {
            is VerdictRow.Judged -> target.recordVerdict(row.key, row.verdict, row.model, row.judgedAt)
            is VerdictRow.Failed -> target.recordVerdictFailure(row.key, row.error, row.attempts, row.retryAt)
        }
    }
    return rows.size
}

class CountReport(
    val matches: Boolean,
    val text: String,
)

/** A table of both sides' counts; it matches only if every table does. */
fun compareCounts(
    sqlite: Map<String, Int>,
    postgres: Map<String, Int>,
): CountReport {
    val lines =
        TABLES.map { table ->
            val left = sqlite.getValue(table)
            val right = postgres.getValue(table)
            "  %-12s %,10d → %,10d %s".format(Locale.US, table, left, right, if (left == right) "✔" else "✖")
        }
    val matches = TABLES.all { sqlite.getValue(it) == postgres.getValue(it) }
    return CountReport(matches, (listOf("  table          sqlite     postgres") + lines).joinToString("\n"))
}

private class FireRow(
    val key: String,
    val signal: Signal,
    val delivered: Boolean,
)

private sealed interface VerdictRow {
    val key: NewsKey

    class Judged(
        override val key: NewsKey,
        val verdict: Verdict,
        val model: String,
        val judgedAt: Instant,
    ) : VerdictRow

    class Failed(
        override val key: NewsKey,
        val error: String,
        val attempts: Int,
        val retryAt: Instant,
    ) : VerdictRow
}

private fun ResultSet.instant(column: String): Instant = Instant.ofEpochMilli(getLong(column))

private fun ResultSet.fire() =
    FireRow(
        key = getString("key"),
        signal = Signal(getString("rule_id"), getString("code"), "", "", instant("fired_at")),
        delivered = getInt("delivered") == 1,
    )

private fun ResultSet.newsItem() =
    NewsItem(
        source = NewsSourceName.fromWire(getString("source")),
        id = getString("id"),
        code = getString("code"),
        title = getString("title"),
        publisher = getString("publisher"),
        url = getString("url"),
        publishedAt = instant("published_at"),
    )

private fun ResultSet.verdictRow(): VerdictRow {
    val key = NewsKey(NewsSourceName.fromWire(getString("source")), getString("id"), getString("code"))
    return if (getString("status") == "judged") {
        VerdictRow.Judged(
            key = key,
            verdict =
                Verdict(
                    relevant = getInt("relevant") == 1,
                    direction = Direction.fromWire(getString("direction")) ?: Direction.NEUTRAL,
                    impact = getDouble("impact"),
                    summary = getString("summary"),
                ),
            model = getString("model"),
            judgedAt = instant("judged_at"),
        )
    } else {
        VerdictRow.Failed(key, getString("last_error").orEmpty(), getInt("attempts"), instant("retry_at"))
    }
}

private fun fail(message: String): Nothing {
    System.err.println("✖ $message")
    exitProcess(1)
}
