package studio.otherstuff.ambulando.core.store

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL

class SqlException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** One column-indexed result row (0-based), valid only inside the row callback. */
class SqlRow internal constructor(private val s: SQLiteStatement) {
    fun isNull(i: Int): Boolean = s.isNull(i)
    fun long(i: Int): Long = s.getLong(i)
    fun int(i: Int): Int = s.getLong(i).toInt()
    fun double(i: Int): Double = s.getDouble(i)
    fun text(i: Int): String? = if (s.isNull(i)) null else s.getText(i)
    fun blob(i: Int): ByteArray = if (s.isNull(i)) ByteArray(0) else s.getBlob(i)
    fun bool(i: Int): Boolean = !s.isNull(i) && s.getLong(i) != 0L
    val columnCount: Int get() = s.getColumnCount()
}

/**
 * One SQLite connection over the bundled SQLite (same engine on Android and in JVM tests, FTS5 included).
 * Not thread-safe by design: each instance is owned by exactly one coroutine owner (writer or reader).
 * Bind values: null, Int, Long, Boolean, Double, String, ByteArray.
 */
class SqlConnection private constructor(private val conn: SQLiteConnection, val readOnly: Boolean) : AutoCloseable {
    private var depth = 0
    private var changesStatement: SQLiteStatement? = null

    companion object {
        private val driver = BundledSQLiteDriver()

        fun open(path: String, readOnly: Boolean = false): SqlConnection {
            val flags = if (readOnly) androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
            else androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE or androidx.sqlite.driver.bundled.SQLITE_OPEN_CREATE
            val c = SqlConnection(driver.open(path, flags), readOnly)
            c.execute("PRAGMA busy_timeout = 2000")
            if (!readOnly) {
                c.query("PRAGMA journal_mode=WAL") { }
                c.execute("PRAGMA synchronous=NORMAL")
                c.execute("PRAGMA foreign_keys=ON")
            }
            c.execute("PRAGMA temp_store=MEMORY")
            return c
        }

        /** Private in-memory database (tests and scratch work). */
        fun memory(): SqlConnection = open(":memory:")
    }

    fun execute(sql: String) = wrap(sql) { conn.execSQL(sql) }

    /** Runs a statement and returns the number of changed rows. */
    fun run(sql: String, vararg args: Any?): Int = wrap(sql) {
        conn.prepare(sql).use { s ->
            bind(s, args)
            while (s.step()) { /* drain */ }
        }
        changes()
    }

    fun <T> query(sql: String, vararg args: Any?, row: (SqlRow) -> T): List<T> = wrap(sql) {
        conn.prepare(sql).use { s ->
            bind(s, args)
            val out = ArrayList<T>()
            val r = SqlRow(s)
            while (s.step()) out.add(row(r))
            out
        }
    }

    /**
     * Compiles `sql` once for a batch (one upsert statement run for up to 500 rows). Close it when the batch is done,
     * normally with `use {}`.
     */
    fun prepare(sql: String): SqlStatement = wrap(sql) { SqlStatement(this, sql, conn.prepare(sql)) }

    /** Runs each statement of a script in order (one statement per entry; trigger bodies may contain `;`). */
    fun executeAll(statements: List<String>) { for (sql in statements) execute(sql) }

    fun <T> first(sql: String, vararg args: Any?, row: (SqlRow) -> T): T? = query(sql, *args, row = row).firstOrNull()

    fun longValue(sql: String, vararg args: Any?): Long? = first(sql, *args) { if (it.isNull(0)) null else it.long(0) }
    fun textValue(sql: String, vararg args: Any?): String? = first(sql, *args) { it.text(0) }

    /** IMMEDIATE transaction; nested calls join the outer one. Rolls back on any exception. */
    fun <T> transaction(body: () -> T): T {
        if (depth > 0) { depth++; try { return body() } finally { depth-- } }
        execute("BEGIN IMMEDIATE")
        depth = 1
        try {
            val result = body()
            depth = 0
            execute("COMMIT")
            return result
        } catch (t: Throwable) {
            depth = 0
            runCatching { execute("ROLLBACK") }
            throw t
        }
    }

    /**
     * DEFERRED read transaction: every statement in `body` reads one WAL snapshot, even while the writer commits.
     * Works on read-only connections; nested calls (or calls inside [transaction]) join the outer one.
     */
    fun <T> snapshot(body: () -> T): T {
        if (depth > 0) { depth++; try { return body() } finally { depth-- } }
        execute("BEGIN DEFERRED")
        depth = 1
        try {
            val result = body()
            depth = 0
            execute("COMMIT")
            return result
        } catch (t: Throwable) {
            depth = 0
            runCatching { execute("ROLLBACK") }
            throw t
        }
    }

    val inTransaction: Boolean get() = depth > 0

    fun changes(): Int {
        val s = changesStatement ?: conn.prepare("SELECT changes()").also { changesStatement = it }
        s.reset()
        s.step()
        return s.getLong(0).toInt()
    }

    var userVersion: Int
        get() = longValue("PRAGMA user_version")?.toInt() ?: 0
        set(value) = execute("PRAGMA user_version = $value")

    override fun close() {
        changesStatement?.close()
        changesStatement = null
        conn.close()
    }

    internal fun bind(s: SQLiteStatement, args: Array<out Any?>) {
        args.forEachIndexed { i, v ->
            val idx = i + 1
            when (v) {
                null -> s.bindNull(idx)
                is Int -> s.bindLong(idx, v.toLong())
                is Long -> s.bindLong(idx, v)
                is Boolean -> s.bindLong(idx, if (v) 1 else 0)
                is Double -> s.bindDouble(idx, v)
                is Float -> s.bindDouble(idx, v.toDouble())
                is String -> s.bindText(idx, v)
                is ByteArray -> s.bindBlob(idx, v)
                else -> throw SqlException("Unsupported bind type ${v::class.simpleName}")
            }
        }
    }

    internal inline fun <T> wrap(sql: String, block: () -> T): T = try {
        block()
    } catch (e: SqlException) {
        throw e
    } catch (e: Exception) {
        // On Android SQLiteException aliases a framework class; never reference the JVM-only class in bytecode.
        val sqlite = generateSequence(e.javaClass as Class<*>?) { it.superclass }
            .any { it.name == "androidx.sqlite.SQLiteException" || it.name == "android.database.sqlite.SQLiteException" }
        if (!sqlite) throw e
        throw SqlException("${e.message} [${sql.take(120)}]", e)
    }
}

/** A statement compiled once and run for many rows of one batch. Owned by the connection's owner; not thread-safe. */
class SqlStatement internal constructor(private val owner: SqlConnection, private val sql: String, private val s: SQLiteStatement) :
    AutoCloseable {
    /** Binds `args`, runs to completion and returns the number of changed rows. */
    fun run(vararg args: Any?): Int = owner.wrap(sql) {
        s.reset()
        s.clearBindings()
        owner.bind(s, args)
        while (s.step()) { /* drain */ }
        owner.changes()
    }

    fun <T> query(vararg args: Any?, row: (SqlRow) -> T): List<T> = owner.wrap(sql) {
        s.reset()
        s.clearBindings()
        owner.bind(s, args)
        val out = ArrayList<T>()
        val r = SqlRow(s)
        while (s.step()) out.add(row(r))
        out
    }

    override fun close() = s.close()
}
