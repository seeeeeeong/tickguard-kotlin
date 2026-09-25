package tickguard.store.sqlite

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import tickguard.orders.CLOSED_STATUSES
import tickguard.orders.Execution
import tickguard.orders.Order
import tickguard.orders.OrderChange
import tickguard.orders.OrderSource
import tickguard.orders.OrderStore
import tickguard.orders.Recorded
import tickguard.orders.supersedes
import tickguard.stream.Decimal
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate

/**
 * The order ledger on SQLite. Decimals are stored as their plain text, which
 * [Decimal] has already normalised, so `10` and `10.0` are the same key in the
 * change log's uniqueness constraint.
 */
internal class SqliteOrders(
    private val db: Connection,
    private val io: CoroutineDispatcher,
) : OrderStore {
    override suspend fun recordOrder(
        order: Order,
        event: String?,
        source: OrderSource,
        seenAt: Instant,
    ): Recorded =
        withContext(io) {
            db.autoCommit = false
            try {
                val recorded = record(order, event, source, seenAt)
                db.commit()
                recorded
            } catch (failure: java.sql.SQLException) {
                db.rollback()
                throw failure
            } finally {
                db.autoCommit = true
            }
        }

    private fun record(
        order: Order,
        event: String?,
        source: OrderSource,
        seenAt: Instant,
    ): Recorded {
        val inserted =
            db
                .prepareStatement(
                    """
                    INSERT OR IGNORE INTO order_events (order_id, event, status, filled_quantity, source, seen_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use {
                    it
                        .bindAll(
                            listOf(
                                order.orderId,
                                event,
                                order.status,
                                order.execution.filledQuantity.toPlainString(),
                                source.wire,
                                seenAt.toEpochMilli(),
                            ),
                        ).executeUpdate()
                }
        if (inserted == 0) return Recorded.REPEAT
        if (!supersedes(order, find(order.orderId))) return Recorded.STALE
        upsert(order, seenAt)
        return Recorded.NEW_STATE
    }

    private fun upsert(
        order: Order,
        at: Instant,
    ) {
        db
            .prepareStatement(
                """
                INSERT OR REPLACE INTO orders (order_id, symbol, side, order_type, time_in_force, status, price,
                  quantity, order_amount, currency, ordered_at, canceled_at, filled_quantity, average_filled_price,
                  filled_amount, commission, tax, settlement_date, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use {
                it.bindAll(order.columns() + at.toEpochMilli()).executeUpdate()
            }
    }

    private fun find(orderId: String): Order? =
        db.prepareStatement("SELECT * FROM orders WHERE order_id = ?").use {
            it.setString(1, orderId)
            it.executeQuery().use { rows -> if (rows.next()) rows.order() else null }
        }

    override suspend fun order(orderId: String): Order? = withContext(io) { find(orderId) }

    override suspend fun openOrders(): List<Order> =
        withContext(io) {
            val closed = CLOSED_STATUSES.joinToString(",") { "?" }
            db
                .prepareStatement(
                    "SELECT * FROM orders WHERE status NOT IN ($closed) ORDER BY ordered_at, order_id",
                ).use {
                    CLOSED_STATUSES.forEachIndexed { index, status -> it.setString(index + 1, status) }
                    it.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.order()) } }
                }
        }

    override suspend fun orderHistory(orderId: String): List<OrderChange> =
        withContext(io) {
            db
                .prepareStatement(
                    "SELECT event, status, filled_quantity, source, seen_at FROM order_events " +
                        "WHERE order_id = ? ORDER BY rowid",
                ).use {
                    it.setString(1, orderId)
                    it.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) {
                                add(
                                    OrderChange(
                                        event = rows.getString("event"),
                                        status = rows.getString("status"),
                                        filledQuantity = rows.required("filled_quantity"),
                                        source = OrderSource.fromWire(rows.getString("source")),
                                        seenAt = Instant.ofEpochMilli(rows.getLong("seen_at")),
                                    ),
                                )
                            }
                        }
                    }
                }
        }

    companion object {
        /** Added with the ledger; `IF NOT EXISTS`, so a file of either origin gains them once. */
        val SCHEMA =
            listOf(
                """
                CREATE TABLE IF NOT EXISTS orders (
                  order_id TEXT PRIMARY KEY, symbol TEXT NOT NULL, side TEXT NOT NULL, order_type TEXT NOT NULL,
                  time_in_force TEXT NOT NULL, status TEXT NOT NULL, price TEXT, quantity TEXT NOT NULL,
                  order_amount TEXT, currency TEXT NOT NULL, ordered_at INTEGER NOT NULL, canceled_at INTEGER,
                  filled_quantity TEXT NOT NULL, average_filled_price TEXT, filled_amount TEXT, commission TEXT,
                  tax TEXT, settlement_date TEXT, updated_at INTEGER NOT NULL
                )
                """,
                "CREATE INDEX IF NOT EXISTS orders_status ON orders (status)",
                """
                CREATE TABLE IF NOT EXISTS order_events (
                  order_id TEXT NOT NULL, event TEXT, status TEXT NOT NULL, filled_quantity TEXT NOT NULL,
                  source TEXT NOT NULL, seen_at INTEGER NOT NULL,
                  UNIQUE (order_id, status, filled_quantity)
                )
                """,
            ).map { it.trimIndent() }
    }
}

private fun ResultSet.decimal(column: String): Decimal? = getString(column)?.let { Decimal.parse(it, column) }

private fun ResultSet.required(column: String): Decimal = decimal(column) ?: error("$column is null")

private fun PreparedStatement.bindAll(values: List<Any?>): PreparedStatement {
    values.forEachIndexed { index, value -> setObject(index + 1, value) }
    return this
}

/** In the column order of the INSERT: decimals as their normalised text, instants as epoch milliseconds. */
private fun Order.columns(): List<Any?> =
    listOf(
        orderId,
        symbol,
        side,
        orderType,
        timeInForce,
        status,
        price?.toPlainString(),
        quantity.toPlainString(),
        orderAmount?.toPlainString(),
        currency,
        orderedAt.toEpochMilli(),
        canceledAt?.toEpochMilli(),
        execution.filledQuantity.toPlainString(),
        execution.averageFilledPrice?.toPlainString(),
        execution.filledAmount?.toPlainString(),
        execution.commission?.toPlainString(),
        execution.tax?.toPlainString(),
        execution.settlementDate?.toString(),
    )

private fun ResultSet.order() =
    Order(
        orderId = getString("order_id"),
        symbol = getString("symbol"),
        side = getString("side"),
        orderType = getString("order_type"),
        timeInForce = getString("time_in_force"),
        status = getString("status"),
        price = decimal("price"),
        quantity = required("quantity"),
        orderAmount = decimal("order_amount"),
        currency = getString("currency"),
        orderedAt = Instant.ofEpochMilli(getLong("ordered_at")),
        canceledAt = getObject("canceled_at")?.let { Instant.ofEpochMilli((it as Number).toLong()) },
        execution =
            Execution(
                filledQuantity = required("filled_quantity"),
                averageFilledPrice = decimal("average_filled_price"),
                filledAmount = decimal("filled_amount"),
                commission = decimal("commission"),
                tax = decimal("tax"),
                settlementDate = getString("settlement_date")?.let(LocalDate::parse),
            ),
    )
