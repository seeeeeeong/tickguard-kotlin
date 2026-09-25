package tickguard.tools

import org.sqlite.SQLiteConfig
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * The service's database, opened read-only. The tools only look; a stray
 * write from one while the service runs would contend for the same file.
 */
fun openReadOnly(env: Map<String, String>): Connection =
    DriverManager.getConnection(
        "jdbc:sqlite:${env["TICKGUARD_DB"] ?: "tickguard.db"}",
        SQLiteConfig().apply { setReadOnly(true) }.toProperties(),
    )

/** Every row [sql] yields, each read by [read]. */
fun <T> Connection.rows(
    sql: String,
    read: (ResultSet) -> T,
): List<T> =
    prepareStatement(sql).use { statement ->
        statement.executeQuery().use { results ->
            buildList { while (results.next()) add(read(results)) }
        }
    }

/** A nullable INTEGER, REAL or TEXT column, as SQLite gave it. */
fun ResultSet.nullable(column: String): Any? = getObject(column)

/** A SQLite table's column names, to read a file an older version wrote. */
fun Connection.columns(table: String): List<String> = rows("PRAGMA table_info($table)") { it.getString("name") }

/** Rows per table in [tables], optionally qualified by a Postgres [schema]. */
fun Connection.counts(
    tables: List<String> = TABLES,
    schema: String? = null,
): Map<String, Int> =
    tables.associateWith { table ->
        val name = if (schema == null) table else "$schema.$table"
        rows("SELECT count(*) AS n FROM $name") { it.getInt("n") }.single()
    }
