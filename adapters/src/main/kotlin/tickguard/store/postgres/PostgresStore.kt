package tickguard.store.postgres

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import tickguard.news.NewsItem
import tickguard.news.NewsKey
import tickguard.news.NewsSourceName
import tickguard.news.StoredNews
import tickguard.rules.Signal
import tickguard.store.Store
import tickguard.store.StoredSignal
import tickguard.store.TickRow
import tickguard.verdict.Direction
import tickguard.verdict.MAX_VERDICT_ATTEMPTS
import tickguard.verdict.PendingStory
import tickguard.verdict.StoredVerdict
import tickguard.verdict.Verdict
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * The store on Postgres, for when one file stops being enough: a job queue and
 * a trade ledger want transactions that more than one process can take part
 * in, and a backtest wants SQL over the ticks.
 *
 * It answers the same contract as the SQLite store, test for test, so moving
 * between them changes where the rows live and nothing that reads them. The
 * types differ where Postgres has a better one: instants are `timestamptz`
 * and prices `numeric`, converted here at the edge.
 *
 * A small pool rather than one connection: the database is across a network,
 * and a slow tick batch should not queue a cooldown write behind it.
 */
class PostgresStore private constructor(
    private val pool: HikariDataSource,
    private val io: CoroutineDispatcher,
) : Store {
    override suspend fun firesSince(since: Instant): Map<String, Instant> =
        query("SELECT key, fired_at FROM fires WHERE fired_at >= ? AND delivered ORDER BY fired_at, key", since) {
            it.getString("key") to it.instant("fired_at")
        }.toMap(LinkedHashMap())

    override suspend fun recordFire(
        key: String,
        signal: Signal,
    ) {
        update(
            """
            INSERT INTO fires (key, rule_id, code, fired_at, delivered) VALUES (?, ?, ?, ?, false)
            ON CONFLICT (key) DO UPDATE SET fired_at = excluded.fired_at, delivered = false
            """,
            key,
            signal.ruleId,
            signal.code,
            signal.firedAt,
        )
    }

    override suspend fun markDelivered(
        key: String,
        firedAt: Instant,
    ) {
        update("UPDATE fires SET delivered = true WHERE key = ? AND fired_at = ?", key, firedAt)
    }

    override suspend fun pruneFires(olderThan: Instant): Int = update("DELETE FROM fires WHERE fired_at < ?", olderThan)

    override suspend fun rejectedTopics(): List<String> =
        query("SELECT target FROM rejections ORDER BY rejected_at, target") { it.getString("target") }

    override suspend fun recordRejection(
        target: String,
        code: String,
        at: Instant,
    ) {
        update(
            """
            INSERT INTO rejections (target, code, rejected_at) VALUES (?, ?, ?)
            ON CONFLICT (target) DO UPDATE SET code = excluded.code, rejected_at = excluded.rejected_at
            """,
            target,
            code,
            at,
        )
    }

    override suspend fun clearRejection(target: String) {
        update("DELETE FROM rejections WHERE target = ?", target)
    }

    override suspend fun recentSignals(limit: Int): List<StoredSignal> =
        query("SELECT key, rule_id, code, fired_at FROM fires ORDER BY fired_at DESC, key LIMIT ?", limit) {
            StoredSignal(it.getString("key"), it.getString("rule_id"), it.getString("code"), it.instant("fired_at"))
        }

    /** One transaction per batch: a batch that fails leaves nothing half-written. */
    override suspend fun recordTicks(ticks: List<TickRow>) =
        transaction { db ->
            db
                .prepareStatement(
                    """
                    INSERT INTO ticks (type, code, price, volume, currency, traded_at, received_at)
                    VALUES (?, ?, ?::numeric, ?::numeric, ?, ?, ?)
                    """.trimIndent(),
                ).use { insert ->
                    for (tick in ticks) {
                        insert.bind(
                            tick.type,
                            tick.code,
                            // Parsed by Postgres, digits and scale as sent.
                            tick.price,
                            tick.volume,
                            tick.currency,
                            tick.tradedAt,
                            tick.receivedAt,
                        )
                        insert.addBatch()
                    }
                    insert.executeBatch()
                }
            Unit
        }

    override suspend fun ticksBetween(
        code: String,
        from: Instant,
        to: Instant,
    ): List<TickRow> =
        query(
            """
            SELECT type, code, price::text AS price, volume::text AS volume, currency, traded_at, received_at
            FROM ticks WHERE code = ? AND traded_at >= ? AND traded_at < ? ORDER BY traded_at, id
            """,
            code,
            from,
            to,
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
        update("DELETE FROM ticks WHERE traded_at < ?", olderThan)

    override suspend fun recordNews(
        item: NewsItem,
        seenAt: Instant,
    ): Boolean =
        update(
            """
            INSERT INTO news (source, id, code, title, publisher, url, published_at, seen_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT DO NOTHING
            """,
            item.source.wire,
            item.id,
            item.code,
            item.title,
            item.publisher,
            item.url,
            item.publishedAt,
            seenAt,
        ) == 1

    override suspend fun newsFor(
        code: String,
        since: Instant,
    ): List<StoredNews> =
        query(
            """
            SELECT source, id, code, title, publisher, url, published_at, seen_at FROM news
            WHERE code = ? AND published_at >= ? ORDER BY published_at DESC, source, id
            """,
            code,
            since,
        ) { it.storedNews() }

    override suspend fun pruneNews(olderThan: Instant): Int =
        update("DELETE FROM news WHERE published_at < ?", olderThan)

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
            ORDER BY n.published_at DESC, n.source, n.id
            LIMIT ?
            """,
            since,
            now,
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
            VALUES (?, ?, ?, 'judged', ?, ?, ?, ?, ?, ?, 1)
            ON CONFLICT (source, id, code) DO UPDATE SET
              status = 'judged', relevant = excluded.relevant, direction = excluded.direction,
              impact = excluded.impact, summary = excluded.summary, model = excluded.model,
              judged_at = excluded.judged_at, attempts = verdicts.attempts + 1, retry_at = NULL, last_error = NULL
            """,
            story.source.wire,
            story.id,
            story.code,
            verdict.relevant,
            verdict.direction.wire,
            verdict.impact,
            verdict.summary,
            model,
            at,
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
            retryAt,
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
                        relevant = it.getBoolean("relevant"),
                        direction = Direction.fromWire(it.getString("direction")) ?: Direction.NEUTRAL,
                        impact = it.getDouble("impact"),
                        summary = it.getString("summary"),
                    ),
                model = it.getString("model"),
                judgedAt = it.instant("judged_at"),
            )
        }.firstOrNull()

    override suspend fun recordModelCall(at: Instant) {
        update("INSERT INTO model_calls (at) VALUES (?)", at)
    }

    override suspend fun modelCallsSince(since: Instant): Int =
        query("SELECT count(*) AS n FROM model_calls WHERE at >= ?", since) { it.getInt("n") }.single()

    override suspend fun pruneVerdicts(olderThan: Instant): Int {
        update("DELETE FROM model_calls WHERE at < ?", olderThan)
        return update(
            "DELETE FROM verdicts WHERE coalesce(judged_at, retry_at, 'epoch'::timestamptz) < ?",
            olderThan,
        )
    }

    override suspend fun close() = withContext(io) { pool.close() }

    private suspend fun update(
        sql: String,
        vararg values: Any,
    ): Int =
        withContext(io) {
            pool.connection.use { db -> db.prepareStatement(sql.trimIndent()).use { it.bind(*values).executeUpdate() } }
        }

    private suspend fun <T> query(
        sql: String,
        vararg values: Any,
        row: (ResultSet) -> T,
    ): List<T> =
        withContext(io) {
            pool.connection.use { db ->
                db.prepareStatement(sql.trimIndent()).use { statement ->
                    statement.bind(*values).executeQuery().use { rows ->
                        buildList { while (rows.next()) add(row(rows)) }
                    }
                }
            }
        }

    private suspend fun <T> transaction(work: (Connection) -> T): T =
        withContext(io) {
            pool.connection.use { db ->
                db.autoCommit = false
                try {
                    work(db).also { db.commit() }
                } catch (failure: java.sql.SQLException) {
                    db.rollback()
                    throw failure
                } finally {
                    db.autoCommit = true
                }
            }
        }

    companion object {
        /** A failure message kept for the record, not a stack trace. */
        private const val LAST_ERROR_LENGTH = 500

        /**
         * Enough for the tick writer, a cooldown write and a news poll at once.
         * The home server's Postgres is shared with another service, so this is
         * kept to what one process can use.
         */
        private const val POOL_SIZE = 4

        /**
         * Opens a pool on [url] and brings [schema] up to the latest migration.
         * Blocks: it runs once, at boot, before there is an engine to keep free.
         * A database that is down fails here, before anything is subscribed,
         * rather than on the first tick; the container's restart policy is what
         * tries again.
         */
        fun open(
            url: String,
            user: String,
            password: String,
            schema: String = "tickguard",
            io: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(POOL_SIZE),
        ): PostgresStore {
            val pool =
                HikariDataSource(
                    HikariConfig().apply {
                        jdbcUrl = url
                        username = user
                        this.password = password
                        this.schema = schema
                        maximumPoolSize = POOL_SIZE
                        poolName = "tickguard-store"
                        // One INSERT with many rows instead of one round trip per tick.
                        addDataSourceProperty("reWriteBatchedInserts", "true")
                    },
                )
            try {
                Flyway
                    .configure()
                    .dataSource(pool)
                    .schemas(schema)
                    .locations("classpath:db/postgres")
                    .load()
                    .migrate()
            } catch (failure: org.flywaydb.core.api.FlywayException) {
                pool.close()
                throw failure
            }
            return PostgresStore(pool, io)
        }
    }
}

private fun PreparedStatement.bind(vararg values: Any): PreparedStatement {
    values.forEachIndexed { index, value ->
        when (value) {
            is Instant -> setObject(index + 1, value.atOffset(ZoneOffset.UTC))
            else -> setObject(index + 1, value)
        }
    }
    return this
}

private fun ResultSet.instant(column: String): Instant = getObject(column, OffsetDateTime::class.java).toInstant()

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
