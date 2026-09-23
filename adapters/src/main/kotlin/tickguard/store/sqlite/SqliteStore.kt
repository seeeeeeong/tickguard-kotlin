package tickguard.store.sqlite

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tickguard.news.NewsItem
import tickguard.news.NewsSourceName
import tickguard.rules.Signal
import tickguard.store.MAX_VERDICT_ATTEMPTS
import tickguard.store.NewsKey
import tickguard.store.PendingStory
import tickguard.store.Store
import tickguard.store.StoredNews
import tickguard.store.StoredSignal
import tickguard.store.StoredVerdict
import tickguard.store.TickRow
import tickguard.verdict.Direction
import tickguard.verdict.Verdict
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant

/**
 * The store on SQLite: in-process and one file to back up.
 *
 * The schema is the original's, byte for byte, and so are the queries. That is
 * what lets the running service be cut over to this one, and rolled back, on
 * the same database file: cooldowns carry over so nothing re-fires, and
 * rejected topics carry over so nothing is re-learned.
 *
 * One connection, used from one thread at a time. node:sqlite answered
 * synchronously, so the original's calls were serial by construction; here
 * every call runs on a dispatcher limited to one thread, which keeps them
 * serial and keeps blocking JDBC off the engine.
 */
class SqliteStore private constructor(
    private val db: Connection,
    private val io: CoroutineDispatcher,
) : Store {
    override suspend fun firesSince(since: Instant): Map<String, Instant> =
        query("SELECT key, fired_at FROM fires WHERE fired_at >= ?", since.toEpochMilli()) {
            it.getString("key") to it.instant("fired_at")
        }.toMap(LinkedHashMap())

    override suspend fun recordFire(
        key: String,
        signal: Signal,
    ) {
        update(
            """
            INSERT INTO fires (key, rule_id, code, fired_at) VALUES (?, ?, ?, ?)
            ON CONFLICT(key) DO UPDATE SET fired_at = excluded.fired_at
            """,
            key,
            signal.ruleId,
            signal.code,
            signal.firedAt.toEpochMilli(),
        )
    }

    override suspend fun pruneFires(olderThan: Instant): Int =
        update("DELETE FROM fires WHERE fired_at < ?", olderThan.toEpochMilli())

    override suspend fun rejectedTopics(): List<String> =
        query("SELECT target FROM rejections") { it.getString("target") }

    override suspend fun recordRejection(
        target: String,
        code: String,
        at: Instant,
    ) {
        update(
            """
            INSERT INTO rejections (target, code, rejected_at) VALUES (?, ?, ?)
            ON CONFLICT(target) DO UPDATE SET code = excluded.code, rejected_at = excluded.rejected_at
            """,
            target,
            code,
            at.toEpochMilli(),
        )
    }

    override suspend fun clearRejection(target: String) {
        update("DELETE FROM rejections WHERE target = ?", target)
    }

    override suspend fun recentSignals(limit: Int): List<StoredSignal> =
        query("SELECT key, rule_id, code, fired_at FROM fires ORDER BY fired_at DESC LIMIT ?", limit) {
            StoredSignal(it.getString("key"), it.getString("rule_id"), it.getString("code"), it.instant("fired_at"))
        }

    /** One transaction per batch: a batch that fails leaves nothing half-written. */
    override suspend fun recordTicks(ticks: List<TickRow>) =
        withContext(io) {
            db.autoCommit = false
            try {
                db
                    .prepareStatement(
                        """
                        INSERT INTO ticks (type, code, price, volume, currency, traded_at, received_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                    ).use { insert ->
                        for (tick in ticks) {
                            insert.bind(
                                tick.type,
                                tick.code,
                                tick.price,
                                tick.volume,
                                tick.currency,
                                tick.tradedAt.toEpochMilli(),
                                tick.receivedAt.toEpochMilli(),
                            )
                            insert.executeUpdate()
                        }
                    }
                db.commit()
            } catch (failure: java.sql.SQLException) {
                db.rollback()
                throw failure
            } finally {
                db.autoCommit = true
            }
        }

    override suspend fun ticksBetween(
        code: String,
        from: Instant,
        to: Instant,
    ): List<TickRow> =
        query(
            """
            SELECT type, code, price, volume, currency, traded_at, received_at FROM ticks
            WHERE code = ? AND traded_at >= ? AND traded_at < ? ORDER BY traded_at, rowid
            """,
            code,
            from.toEpochMilli(),
            to.toEpochMilli(),
        ) {
            TickRow(
                type = it.getString("type"),
                code = it.getString("code"),
                price = it.getString("price"),
                volume = it.getString("volume"),
                currency = it.getString("currency"),
                tradedAt = it.instant("traded_at"),
                receivedAt = it.instant("received_at"),
            )
        }

    override suspend fun pruneTicks(olderThan: Instant): Int =
        update("DELETE FROM ticks WHERE traded_at < ?", olderThan.toEpochMilli())

    override suspend fun recordNews(
        item: NewsItem,
        seenAt: Instant,
    ): Boolean =
        update(
            """
            INSERT OR IGNORE INTO news (source, id, code, title, publisher, url, published_at, seen_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            item.source.wire,
            item.id,
            item.code,
            item.title,
            item.publisher,
            item.url,
            item.publishedAt.toEpochMilli(),
            seenAt.toEpochMilli(),
        ) == 1

    override suspend fun newsFor(
        code: String,
        since: Instant,
    ): List<StoredNews> =
        query(
            """
            SELECT source, id, code, title, publisher, url, published_at, seen_at FROM news
            WHERE code = ? AND published_at >= ? ORDER BY published_at DESC
            """,
            code,
            since.toEpochMilli(),
        ) { it.storedNews() }

    override suspend fun pruneNews(olderThan: Instant): Int =
        update("DELETE FROM news WHERE published_at < ?", olderThan.toEpochMilli())

    override suspend fun pendingVerdicts(
        since: Instant,
        now: Instant,
        limit: Int,
    ): List<PendingStory> =
        query(
            """
            SELECT n.source, n.id, n.code, n.title, n.publisher, n.url, n.published_at, n.seen_at,
                   coalesce(v.attempts, 0) AS attempts
            FROM news n LEFT JOIN verdicts v USING (source, id, code)
            WHERE n.published_at >= ?
              AND (v.status IS NULL OR (v.status = 'failed' AND v.retry_at <= ? AND v.attempts < ?))
            ORDER BY n.published_at DESC
            LIMIT ?
            """,
            since.toEpochMilli(),
            now.toEpochMilli(),
            MAX_VERDICT_ATTEMPTS,
            limit,
        ) { PendingStory(it.storedNews(), it.getInt("attempts")) }

    override suspend fun recordVerdict(
        story: NewsKey,
        verdict: Verdict,
        model: String,
        at: Instant,
    ) {
        update(
            """
            INSERT INTO verdicts (source, id, code, status, relevant, direction, impact, summary, model, judged_at, attempts)
            VALUES (?, ?, ?, 'judged', ?, ?, ?, ?, ?, ?, coalesce((SELECT attempts FROM verdicts WHERE source = ? AND id = ? AND code = ?), 0) + 1)
            ON CONFLICT (source, id, code) DO UPDATE SET
              status = 'judged', relevant = excluded.relevant, direction = excluded.direction,
              impact = excluded.impact, summary = excluded.summary, model = excluded.model,
              judged_at = excluded.judged_at, attempts = excluded.attempts, retry_at = NULL, last_error = NULL
            """,
            story.source.wire,
            story.id,
            story.code,
            if (verdict.relevant) 1 else 0,
            verdict.direction.wire,
            verdict.impact,
            verdict.summary,
            model,
            at.toEpochMilli(),
            story.source.wire,
            story.id,
            story.code,
        )
    }

    override suspend fun recordVerdictFailure(
        story: NewsKey,
        error: String,
        attempts: Int,
        retryAt: Instant,
    ) {
        update(
            """
            INSERT INTO verdicts (source, id, code, status, attempts, retry_at, last_error)
            VALUES (?, ?, ?, 'failed', ?, ?, ?)
            ON CONFLICT (source, id, code) DO UPDATE SET
              status = 'failed', attempts = excluded.attempts, retry_at = excluded.retry_at,
              last_error = excluded.last_error
            """,
            story.source.wire,
            story.id,
            story.code,
            attempts,
            retryAt.toEpochMilli(),
            error.take(LAST_ERROR_LENGTH),
        )
    }

    override suspend fun verdictFor(story: NewsKey): StoredVerdict? =
        query(
            """
            SELECT relevant, direction, impact, summary, model, judged_at FROM verdicts
            WHERE source = ? AND id = ? AND code = ? AND status = 'judged'
            """,
            story.source.wire,
            story.id,
            story.code,
        ) {
            StoredVerdict(
                verdict =
                    Verdict(
                        relevant = it.getInt("relevant") == 1,
                        direction = Direction.fromWire(it.getString("direction")) ?: Direction.NEUTRAL,
                        impact = it.getDouble("impact"),
                        summary = it.getString("summary"),
                    ),
                model = it.getString("model"),
                judgedAt = it.instant("judged_at"),
            )
        }.firstOrNull()

    override suspend fun recordModelCall(at: Instant) {
        update("INSERT INTO model_calls (at) VALUES (?)", at.toEpochMilli())
    }

    override suspend fun modelCallsSince(since: Instant): Int =
        query("SELECT count(*) AS n FROM model_calls WHERE at >= ?", since.toEpochMilli()) { it.getInt("n") }.single()

    override suspend fun pruneVerdicts(olderThan: Instant): Int {
        update("DELETE FROM model_calls WHERE at < ?", olderThan.toEpochMilli())
        return update("DELETE FROM verdicts WHERE coalesce(judged_at, retry_at, 0) < ?", olderThan.toEpochMilli())
    }

    override suspend fun close() = withContext(io) { db.close() }

    private suspend fun update(
        sql: String,
        vararg values: Any,
    ): Int = withContext(io) { db.prepareStatement(sql.trimIndent()).use { it.bind(*values).executeUpdate() } }

    private suspend fun <T> query(
        sql: String,
        vararg values: Any,
        row: (ResultSet) -> T,
    ): List<T> =
        withContext(io) {
            db.prepareStatement(sql.trimIndent()).use { statement ->
                statement.bind(*values).executeQuery().use { rows ->
                    buildList { while (rows.next()) add(row(rows)) }
                }
            }
        }

    companion object {
        /** A failure message kept for the record, not a stack trace. */
        private const val LAST_ERROR_LENGTH = 500

        /**
         * Opens, or creates, the database at [path]. Opening may create a file
         * and fail doing so, which belongs before the app exists rather than
         * inside a constructor.
         */
        suspend fun open(
            path: String = "tickguard.db",
            io: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
        ): SqliteStore =
            withContext(io) {
                val db = DriverManager.getConnection("jdbc:sqlite:$path")
                // The original ran the schema whole; JDBC runs one statement at a
                // time. Comments go first, because one contains a semicolon. SQLite
                // does not keep a comment that precedes a CREATE, so the tables it
                // records are the same either way.
                SCHEMA
                    .lines()
                    .filterNot { it.trim().startsWith("--") }
                    .joinToString("\n")
                    .split(";")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .forEach { sql -> db.createStatement().use { it.execute(sql) } }
                SqliteStore(db, io)
            }
    }
}

private fun PreparedStatement.bind(vararg values: Any): PreparedStatement {
    values.forEachIndexed { index, value -> setObject(index + 1, value) }
    return this
}

private fun ResultSet.instant(column: String): Instant = Instant.ofEpochMilli(getLong(column))

private fun ResultSet.storedNews() =
    StoredNews(
        item =
            NewsItem(
                source = NewsSourceName.fromWire(getString("source")),
                id = getString("id"),
                code = getString("code"),
                title = getString("title"),
                publisher = getString("publisher"),
                url = getString("url"),
                publishedAt = instant("published_at"),
            ),
        seenAt = instant("seen_at"),
    )

/** The original's schema, unchanged, comments and all. */
private val SCHEMA =
    """
    PRAGMA journal_mode = WAL;
    PRAGMA synchronous = NORMAL;

    CREATE TABLE IF NOT EXISTS fires (
      key       TEXT PRIMARY KEY,
      rule_id   TEXT NOT NULL,
      code      TEXT NOT NULL,
      fired_at  INTEGER NOT NULL
    );
    CREATE INDEX IF NOT EXISTS fires_fired_at ON fires (fired_at);

    CREATE TABLE IF NOT EXISTS rejections (
      target      TEXT PRIMARY KEY,
      code        TEXT NOT NULL,
      rejected_at INTEGER NOT NULL
    );

    -- No primary key beyond rowid: quotes carry no sequence number, and two
    -- identical trades in the same millisecond are two trades.
    CREATE TABLE IF NOT EXISTS ticks (
      type        TEXT NOT NULL,
      code        TEXT NOT NULL,
      price       TEXT NOT NULL,
      volume      TEXT NOT NULL,
      currency    TEXT NOT NULL,
      traded_at   INTEGER NOT NULL,
      received_at INTEGER NOT NULL
    );
    -- A replay reads one symbol over a range; pruning cuts across all of them.
    CREATE INDEX IF NOT EXISTS ticks_code_traded_at ON ticks (code, traded_at);
    CREATE INDEX IF NOT EXISTS ticks_traded_at ON ticks (traded_at);

    -- Keyed per symbol as well: one story about two held companies is news
    -- for both.
    CREATE TABLE IF NOT EXISTS news (
      source       TEXT NOT NULL,
      id           TEXT NOT NULL,
      code         TEXT NOT NULL,
      title        TEXT NOT NULL,
      publisher    TEXT NOT NULL,
      url          TEXT NOT NULL,
      published_at INTEGER NOT NULL,
      seen_at      INTEGER NOT NULL,
      PRIMARY KEY (source, id, code)
    );
    CREATE INDEX IF NOT EXISTS news_code_published_at ON news (code, published_at);

    -- One row per story. status 'failed' with attempts below the limit is
    -- retried at retry_at; 'judged' is final.
    CREATE TABLE IF NOT EXISTS verdicts (
      source     TEXT NOT NULL,
      id         TEXT NOT NULL,
      code       TEXT NOT NULL,
      status     TEXT NOT NULL,
      relevant   INTEGER,
      direction  TEXT,
      impact     REAL,
      summary    TEXT,
      model      TEXT,
      judged_at  INTEGER,
      attempts   INTEGER NOT NULL DEFAULT 0,
      retry_at   INTEGER,
      last_error TEXT,
      PRIMARY KEY (source, id, code)
    );

    CREATE TABLE IF NOT EXISTS model_calls (at INTEGER NOT NULL);
    CREATE INDEX IF NOT EXISTS model_calls_at ON model_calls (at);
    """.trimIndent()
