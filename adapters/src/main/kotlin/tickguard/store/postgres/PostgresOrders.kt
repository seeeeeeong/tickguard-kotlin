package tickguard.store.postgres

import com.zaxxer.hikari.HikariDataSource
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
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * The order ledger on Postgres. Recording a change is one transaction that
 * locks the order's row, so a stream event and a resync landing together
 * cannot both read the old state and both write theirs.
 */
internal class PostgresOrders(
    private val pool: HikariDataSource,
    private val io: CoroutineDispatcher,
) : OrderStore {
    override suspend fun recordOrder(
        order: Order,
        event: String?,
        source: OrderSource,
        seenAt: Instant,
    ): Recorded =
        withContext(io) {
            pool.connection.use { db ->
                db.autoCommit = false
                try {
                    record(db, order, event, source, seenAt).also { db.commit() }
                } catch (failure: java.sql.SQLException) {
                    db.rollback()
                    throw failure
                } finally {
                    db.autoCommit = true
                }
            }
        }

    private fun record(
        db: Connection,
        order: Order,
        event: String?,
        source: OrderSource,
        seenAt: Instant,
    ): Recorded {
        val inserted =
            db
                .prepareStatement(
                    """
                    INSERT INTO order_events (order_id, event, status, filled_quantity, source, seen_at)
                    VALUES (?, ?, ?, ?::numeric, ?, ?::timestamptz) ON CONFLICT DO NOTHING
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
                                seenAt.utc(),
                            ),
                        ).executeUpdate()
                }
        if (inserted == 0) return Recorded.REPEAT
        val held = find(db, order.orderId, lock = true)
        if (!supersedes(order, held)) return Recorded.STALE
        upsert(db, order, seenAt)
        return Recorded.NEW_STATE
    }

    private fun upsert(
        db: Connection,
        order: Order,
        at: Instant,
    ) {
        db
            .prepareStatement(
                """
                INSERT INTO orders (order_id, symbol, side, order_type, time_in_force, status, price, quantity,
                  order_amount, currency, ordered_at, canceled_at, filled_quantity, average_filled_price,
                  filled_amount, commission, tax, settlement_date, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?::numeric, ?::numeric, ?::numeric, ?, ?::timestamptz, ?::timestamptz,
                  ?::numeric, ?::numeric, ?::numeric, ?::numeric, ?::numeric, ?::date, ?::timestamptz)
                ON CONFLICT (order_id) DO UPDATE SET
                  status = excluded.status, price = excluded.price, quantity = excluded.quantity,
                  order_amount = excluded.order_amount, canceled_at = excluded.canceled_at,
                  filled_quantity = excluded.filled_quantity, average_filled_price = excluded.average_filled_price,
                  filled_amount = excluded.filled_amount, commission = excluded.commission, tax = excluded.tax,
                  settlement_date = excluded.settlement_date, updated_at = excluded.updated_at
                """.trimIndent(),
            ).use {
                it.bindAll(order.columns() + at.utc()).executeUpdate()
            }
    }

    private fun find(
        db: Connection,
        orderId: String,
        lock: Boolean = false,
    ): Order? =
        db.prepareStatement("$SELECT_ORDER WHERE order_id = ?${if (lock) " FOR UPDATE" else ""}").use {
            it.setString(1, orderId)
            it.executeQuery().use { rows -> if (rows.next()) rows.order() else null }
        }

    override suspend fun order(orderId: String): Order? = withContext(io) { pool.connection.use { find(it, orderId) } }

    override suspend fun openOrders(): List<Order> =
        withContext(io) {
            pool.connection.use { db ->
                db.prepareStatement("$SELECT_ORDER WHERE NOT (status = ANY (?)) ORDER BY ordered_at, order_id").use {
                    it.setArray(1, db.createArrayOf("text", CLOSED_STATUSES.toTypedArray()))
                    it.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.order()) } }
                }
            }
        }

    override suspend fun orderHistory(orderId: String): List<OrderChange> =
        withContext(io) {
            pool.connection.use { db ->
                db
                    .prepareStatement(
                        """
                        SELECT event, status, filled_quantity::text AS filled_quantity, source, seen_at
                        FROM order_events WHERE order_id = ? ORDER BY id
                        """.trimIndent(),
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
                                            seenAt = rows.instant("seen_at") ?: error("order_events.seen_at is null"),
                                        ),
                                    )
                                }
                            }
                        }
                    }
            }
        }

    private companion object {
        /** Numerics read as text, so [Decimal] parses the digits Postgres holds. */
        const val SELECT_ORDER =
            "SELECT order_id, symbol, side, order_type, time_in_force, status, price::text AS price, " +
                "quantity::text AS quantity, order_amount::text AS order_amount, currency, ordered_at, " +
                "canceled_at, filled_quantity::text AS filled_quantity, " +
                "average_filled_price::text AS average_filled_price, filled_amount::text AS filled_amount, " +
                "commission::text AS commission, tax::text AS tax, settlement_date FROM orders"
    }
}

private fun ResultSet.decimal(column: String): Decimal? = getString(column)?.let { Decimal.parse(it, column) }

private fun ResultSet.required(column: String): Decimal = decimal(column) ?: error("$column is null")

private fun Instant.utc(): OffsetDateTime = atOffset(ZoneOffset.UTC)

private fun PreparedStatement.bindAll(values: List<Any?>): PreparedStatement {
    values.forEachIndexed { index, value -> setObject(index + 1, value) }
    return this
}

/** In the column order of the INSERT: text for every decimal, which the SQL casts. */
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
        orderedAt.utc(),
        canceledAt?.utc(),
        execution.filledQuantity.toPlainString(),
        execution.averageFilledPrice?.toPlainString(),
        execution.filledAmount?.toPlainString(),
        execution.commission?.toPlainString(),
        execution.tax?.toPlainString(),
        execution.settlementDate?.toString(),
    )

private fun ResultSet.instant(column: String): Instant? = getObject(column, OffsetDateTime::class.java)?.toInstant()

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
        orderedAt = instant("ordered_at") ?: error("orders.ordered_at is null"),
        canceledAt = instant("canceled_at"),
        execution =
            Execution(
                filledQuantity = required("filled_quantity"),
                averageFilledPrice = decimal("average_filled_price"),
                filledAmount = decimal("filled_amount"),
                commission = decimal("commission"),
                tax = decimal("tax"),
                settlementDate = getObject("settlement_date", java.time.LocalDate::class.java),
            ),
    )
