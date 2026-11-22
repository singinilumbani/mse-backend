package com.example


import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

// ONE connection to the database file, shared by the whole server.
// All access goes through transaction { }, which takes a lock, so two requests can never
// change the same data at the same moment.
object Db {

    // The tables. "IF NOT EXISTS" means the server can start many times without losing data.
    private val schema = listOf(
        """CREATE TABLE IF NOT EXISTS users (
            id            INTEGER PRIMARY KEY AUTOINCREMENT,
            full_name     TEXT NOT NULL,
            identifier    TEXT NOT NULL UNIQUE,
            password_hash TEXT NOT NULL,
            role          TEXT NOT NULL DEFAULT 'INVESTOR' CHECK (role IN ('INVESTOR', 'ADMIN'))
        )""",
        """CREATE TABLE IF NOT EXISTS accounts (
            user_id    INTEGER PRIMARY KEY REFERENCES users(id),
            cash_cents INTEGER NOT NULL DEFAULT 0 CHECK (cash_cents >= 0)
        )""",
        """CREATE TABLE IF NOT EXISTS holdings (
            user_id  INTEGER NOT NULL REFERENCES users(id),
            symbol   TEXT NOT NULL,
            quantity INTEGER NOT NULL CHECK (quantity > 0),
            avg_cost TEXT NOT NULL,
            PRIMARY KEY (user_id, symbol)
        )""",
        """CREATE TABLE IF NOT EXISTS transactions (
            id          INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id     INTEGER NOT NULL REFERENCES users(id),
            type        TEXT NOT NULL CHECK (type IN ('BUY', 'SELL', 'DEPOSIT', 'WITHDRAWAL')),
            symbol      TEXT,
            quantity    INTEGER NOT NULL,
            price_cents INTEGER NOT NULL,
            date        TEXT NOT NULL
        )""",
        """CREATE TABLE IF NOT EXISTS orders (
            id          INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id     INTEGER NOT NULL REFERENCES users(id),
            symbol      TEXT NOT NULL,
            side        TEXT NOT NULL CHECK (side IN ('BUY', 'SELL')),
            quantity    INTEGER NOT NULL CHECK (quantity > 0),
            price_cents INTEGER NOT NULL,
            fees_cents  INTEGER NOT NULL,
            cost_basis  TEXT NOT NULL,
            status      TEXT NOT NULL CHECK (status IN ('SUBMITTED', 'FILLED', 'REJECTED')),
            created_at  TEXT NOT NULL
        )""",
        """CREATE TABLE IF NOT EXISTS kyc (
            user_id          INTEGER PRIMARY KEY REFERENCES users(id),
            status           TEXT NOT NULL CHECK (status IN ('NOT_STARTED', 'IN_PROGRESS', 'PENDING', 'REJECTED', 'APPROVED')),
            rejection_reason TEXT,
            submitted_at     TEXT,
            draft_json       TEXT NOT NULL
        )""",
        // Indexes make "all orders of this user" fast, even with millions of rows
        "CREATE INDEX IF NOT EXISTS idx_orders_user ON orders (user_id, id)",
        "CREATE INDEX IF NOT EXISTS idx_transactions_user ON transactions (user_id, id)",
    )

    private val lock = Any()
    private var depth = 0
    private val connection: Connection

    init {
        // The file name comes from a setting, so unit tests can use a database in memory instead
        val target = System.getProperty("mse.db") ?: System.getenv("MSE_DB") ?: "mse.db"
        val inMemory = target == ":memory:"

        connection = DriverManager.getConnection(
            if (inMemory) "jdbc:sqlite::memory:" else "jdbc:sqlite:$target"
        )
        connection.createStatement().use { st ->
            st.execute("PRAGMA foreign_keys = ON")                 // SQLite ignores foreign keys unless asked
            if (!inMemory) st.execute("PRAGMA journal_mode = WAL")  // safer if the server stops suddenly
        }
        connection.createStatement().use { st -> schema.forEach { st.execute(it) } }
    }

    // Runs the block as ONE unit: either every change is saved, or none of them.
    // If the block throws (for example a RuleException), everything it did is undone.
    fun <T> transaction(block: (Connection) -> T): T = synchronized(lock) {
        val outermost = depth == 0      // a transaction inside a transaction joins the outer one
        depth++
        try {
            if (outermost) connection.autoCommit = false
            val result = block(connection)
            if (outermost) connection.commit()
            result
        } catch (e: Throwable) {
            if (outermost) connection.rollback()
            throw e
        } finally {
            depth--
            if (outermost) connection.autoCommit = true
        }
    }
}

// ---------- Two small tools for writing SQL ----------
// The values go in through "?" placeholders and are NEVER glued into the SQL text.
// That is what makes SQL injection impossible.

// Runs a SELECT and turns every row into an object
fun <T> Connection.query(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { ps ->
        args.forEachIndexed { i, arg -> ps.setObject(i + 1, arg) }
        ps.executeQuery().use { rs ->
            val rows = mutableListOf<T>()
            while (rs.next()) rows.add(map(rs))
            rows
        }
    }

// Runs an INSERT, UPDATE or DELETE. Returns how many rows changed.
fun Connection.execute(sql: String, vararg args: Any?): Int =
    prepareStatement(sql).use { ps ->
        args.forEachIndexed { i, arg -> ps.setObject(i + 1, arg) }
        ps.executeUpdate()
    }

// The id the database gave to the row we just inserted
fun Connection.insertedId(): Long = query("SELECT last_insert_rowid()") { it.getLong(1) }.first()

// ---------- Money ----------
// In the database, money is a whole number of tambala (MK 12.50 is stored as 1250). Integers are exact.
fun BigDecimal.toCents(): Long = movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()
fun Long.toMoney(): BigDecimal = BigDecimal.valueOf(this, 2)