package tickguard.store.postgres

import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import tickguard.stream.Decimal
import tickguard.trading.Bar
import tickguard.trading.BarStore
import java.sql.ResultSet
import java.time.LocalDate

/** Daily bars on Postgres: numeric prices bound as text and cast, so nothing is rounded on the way in. */
internal class PostgresBars(
    private val pool: HikariDataSource,
    private val io: CoroutineDispatcher,
) : BarStore {
    override suspend fun recordBars(bars: List<Bar>) =
        withContext(io) {
            pool.connection.use { db ->
                db.autoCommit = false
                try {
                    db
                        .prepareStatement(
                            """
                            INSERT INTO bars (code, day, open, high, low, close, volume)
                            VALUES (?, ?::date, ?::numeric, ?::numeric, ?::numeric, ?::numeric, ?::numeric)
                            ON CONFLICT (code, day) DO UPDATE SET open = excluded.open, high = excluded.high,
                              low = excluded.low, close = excluded.close, volume = excluded.volume
                            """.trimIndent(),
                        ).use { insert ->
                            for (bar in bars) {
                                listOf(
                                    bar.code,
                                    bar.day.toString(),
                                    bar.open.toPlainString(),
                                    bar.high.toPlainString(),
                                    bar.low.toPlainString(),
                                    bar.close.toPlainString(),
                                    bar.volume.toPlainString(),
                                ).forEachIndexed { index, value -> insert.setString(index + 1, value) }
                                insert.addBatch()
                            }
                            insert.executeBatch()
                        }
                    db.commit()
                } catch (failure: java.sql.SQLException) {
                    db.rollback()
                    throw failure
                } finally {
                    db.autoCommit = true
                }
            }
        }

    override suspend fun bars(
        code: String,
        from: LocalDate,
        to: LocalDate,
    ): List<Bar> =
        withContext(io) {
            pool.connection.use { db ->
                db
                    .prepareStatement(
                        "SELECT code, day, open::text AS open, high::text AS high, low::text AS low, " +
                            "close::text AS close, volume::text AS volume FROM bars " +
                            "WHERE code = ? AND day >= ? AND day <= ? ORDER BY day",
                    ).use {
                        listOf<Any>(code, from, to).forEachIndexed { index, value -> it.setObject(index + 1, value) }
                        it.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.bar()) } }
                    }
            }
        }
}

private fun ResultSet.bar() =
    Bar(
        code = getString("code"),
        day = getObject("day", LocalDate::class.java),
        open = Decimal.parse(getString("open"), "open"),
        high = Decimal.parse(getString("high"), "high"),
        low = Decimal.parse(getString("low"), "low"),
        close = Decimal.parse(getString("close"), "close"),
        volume = Decimal.parse(getString("volume"), "volume"),
    )
