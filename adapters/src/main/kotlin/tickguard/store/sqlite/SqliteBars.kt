package tickguard.store.sqlite

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import tickguard.stream.Decimal
import tickguard.trading.Bar
import tickguard.trading.BarStore
import java.sql.Connection
import java.sql.ResultSet
import java.time.LocalDate

/** Daily bars on SQLite: prices as normalised text, days as ISO dates, which sort as they read. */
internal class SqliteBars(
    private val db: Connection,
    private val io: CoroutineDispatcher,
) : BarStore {
    override suspend fun recordBars(bars: List<Bar>) =
        withContext(io) {
            db.autoCommit = false
            try {
                db
                    .prepareStatement(
                        "INSERT OR REPLACE INTO bars (code, day, open, high, low, close, volume) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?)",
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

    override suspend fun bars(
        code: String,
        from: LocalDate,
        to: LocalDate,
    ): List<Bar> =
        withContext(io) {
            db.prepareStatement("SELECT * FROM bars WHERE code = ? AND day >= ? AND day <= ? ORDER BY day").use {
                listOf(code, from.toString(), to.toString()).forEachIndexed {
                    index,
                    value,
                    ->
                    it.setString(index + 1, value)
                }
                it.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.bar()) } }
            }
        }

    companion object {
        /** Added with backtesting; `IF NOT EXISTS`, so a file of either origin gains it once. */
        const val SCHEMA =
            "CREATE TABLE IF NOT EXISTS bars (code TEXT NOT NULL, day TEXT NOT NULL, open TEXT NOT NULL, " +
                "high TEXT NOT NULL, low TEXT NOT NULL, close TEXT NOT NULL, volume TEXT NOT NULL, PRIMARY KEY (code, day))"
    }
}

private fun ResultSet.bar() =
    Bar(
        code = getString("code"),
        day = LocalDate.parse(getString("day")),
        open = Decimal.parse(getString("open"), "open"),
        high = Decimal.parse(getString("high"), "high"),
        low = Decimal.parse(getString("low"), "low"),
        close = Decimal.parse(getString("close"), "close"),
        volume = Decimal.parse(getString("volume"), "volume"),
    )
