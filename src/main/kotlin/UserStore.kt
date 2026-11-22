package com.example

import java.sql.ResultSet

// The users, now saved in the database
object UserStore {

    private const val COLUMNS = "id, full_name, identifier, password_hash, role"

    private fun ResultSet.toUser() = User(
        id = getInt("id"),
        fullName = getString("full_name"),
        identifier = getString("identifier"),
        passwordHash = getString("password_hash"),
        role = getString("role")
    )

    init {
        // Only on the very first start, when the table is empty
        val isEmpty = Db.transaction { c -> c.query("SELECT COUNT(*) FROM users") { it.getInt(1) }.first() == 0 }
        if (isEmpty) {
            // A ready-made investor for testing: demo@mse.test / Password1, with starting money and shares.
            // Its registration is NOT approved, so you can try the whole registration process.
            val demo = register("Demo User", "demo@mse.test", "Password1")
            if (demo != null) AccountStore.seedDemo(demo.id)

            // The reviewer account. In the cloud there is NO default password: the account only
            // exists when ADMIN_PASSWORD is set. On your own computer it is admin@mse.test / Admin12345.
            val adminPassword = System.getenv("ADMIN_PASSWORD")
                ?: if (System.getenv("PORT") == null) "Admin12345" else null
            if (adminPassword != null) register("Reviewer", "admin@mse.test", adminPassword, role = "ADMIN")
        }
    }

    fun initialize() {
        // Calling this function triggers the UserStore initialization.
    }
    // "Jane@Mail.com " and "jane@mail.com" are the same account
    fun normalise(identifier: String): String = identifier.trim().lowercase()

    fun findByIdentifier(identifier: String): User? = Db.transaction { c ->
        c.query("SELECT $COLUMNS FROM users WHERE identifier = ?", normalise(identifier)) { it.toUser() }
            .firstOrNull()
    }

    fun findById(id: Int): User? = Db.transaction { c ->
        c.query("SELECT $COLUMNS FROM users WHERE id = ?", id) { it.toUser() }.firstOrNull()
    }

    // Returns the new user, or null when the email or number is already taken.
    // The public sign-up route never passes a role, so strangers can only ever become investors.
    fun register(fullName: String, identifier: String, password: String, role: String = "INVESTOR"): User? {
        val key = normalise(identifier)
        // bcrypt is slow on purpose, so it is done BEFORE taking the database lock
        val hash = PasswordHasher.hash(password)

        return Db.transaction { c ->
            val taken = c.query("SELECT 1 FROM users WHERE identifier = ?", key) { 1 }.isNotEmpty()
            if (taken) {
                null
            } else {
                c.execute(
                    "INSERT INTO users (full_name, identifier, password_hash, role) VALUES (?, ?, ?, ?)",
                    fullName.trim(), key, hash, role
                )
                User(c.insertedId().toInt(), fullName.trim(), key, hash, role)
            }
        }
    }
}