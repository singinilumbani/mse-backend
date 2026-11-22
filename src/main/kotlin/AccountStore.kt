package com.example

import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.Connection
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// A business rule was broken. The app receives 422 and this message.
class RuleException(message: String) : RuntimeException(message)

// ---------- Money helpers. BigDecimal is exact, so there are no rounding surprises. ----------
private val FEE_RATE = BigDecimal("0.02")            // sample 2% fee
private val MIN_TRANSFER = BigDecimal("1000")
private val MAX_DEPOSIT = BigDecimal("5000000")
private const val MAX_ORDER_QUANTITY = 1_000_000
private const val REJECTED_QUANTITY = 13             // the simulated broker always rejects exactly 13 shares

private fun BigDecimal.money(): BigDecimal = setScale(2, RoundingMode.HALF_UP)
private fun text(amount: BigDecimal): String = String.format(Locale.US, "%,.2f", amount.money())
private fun today(): String = LocalDate.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.US))

internal class Position(val quantity: Int, val avgCost: BigDecimal)

internal class OrderRow(
    val id: Int, val symbol: String, val side: String, val quantity: Int,
    val price: BigDecimal, val fees: BigDecimal,
    val costBasis: BigDecimal,       // the average cost at the time of a sell, so a rejection can restore it
    val status: String
)

object AccountStore {

    // ---------- Small helpers. They all work inside the caller's transaction. ----------

    // The user's cash. A user who has no account row yet gets one with MK 0.
    private fun cashOf(c: Connection, userId: Int): BigDecimal {
        c.execute("INSERT OR IGNORE INTO accounts (user_id, cash_cents) VALUES (?, 0)", userId)
        return c.query("SELECT cash_cents FROM accounts WHERE user_id = ?", userId) { it.getLong(1).toMoney() }.first()
    }

    private fun setCash(c: Connection, userId: Int, amount: BigDecimal) {
        c.execute("UPDATE accounts SET cash_cents = ? WHERE user_id = ?", amount.toCents(), userId)
    }

    private fun positionOf(c: Connection, userId: Int, symbol: String): Position? =
        c.query("SELECT quantity, avg_cost FROM holdings WHERE user_id = ? AND symbol = ?", userId, symbol) {
            Position(it.getInt(1), BigDecimal(it.getString(2)))
        }.firstOrNull()

    private fun addShares(c: Connection, userId: Int, symbol: String, quantity: Int, price: BigDecimal) {
        val existing = positionOf(c, userId, symbol)
        if (existing == null) {
            c.execute(
                "INSERT INTO holdings (user_id, symbol, quantity, avg_cost) VALUES (?, ?, ?, ?)",
                userId, symbol, quantity, price.toPlainString()
            )
        } else {
            val total = existing.quantity + quantity
            val cost = existing.avgCost.multiply(BigDecimal.valueOf(existing.quantity.toLong())) +
                    price.multiply(BigDecimal.valueOf(quantity.toLong()))
            val average = cost.divide(BigDecimal.valueOf(total.toLong()), 6, RoundingMode.HALF_UP)
            c.execute(
                "UPDATE holdings SET quantity = ?, avg_cost = ? WHERE user_id = ? AND symbol = ?",
                total, average.toPlainString(), userId, symbol
            )
        }
    }

    private fun takeShares(c: Connection, userId: Int, symbol: String, quantity: Int) {
        val position = positionOf(c, userId, symbol) ?: return
        if (position.quantity <= quantity) {
            c.execute("DELETE FROM holdings WHERE user_id = ? AND symbol = ?", userId, symbol)
        } else {
            c.execute(
                "UPDATE holdings SET quantity = quantity - ? WHERE user_id = ? AND symbol = ?",
                quantity, userId, symbol
            )
        }
    }

    // For a deposit or withdrawal, "price" is the amount of money and quantity is 0
    private fun record(
        c: Connection, userId: Int, type: String, symbol: String?,
        quantity: Int, price: BigDecimal, date: String = today()
    ) {
        c.execute(
            "INSERT INTO transactions (user_id, type, symbol, quantity, price_cents, date) VALUES (?, ?, ?, ?, ?, ?)",
            userId, type, symbol, quantity, price.toCents(), date
        )
    }

    private fun setStatus(c: Connection, orderId: Int, status: String) {

        c.execute("UPDATE orders SET status = ? WHERE id = ?", status, orderId)
    }

    // ---------- Reading ----------

    fun snapshot(userId: Int): AccountDto = Db.transaction { c ->
        AccountDto(
            cash = cashOf(c, userId).toDouble(),
            holdings = c.query(
                "SELECT symbol, quantity, avg_cost FROM holdings WHERE user_id = ? ORDER BY rowid", userId
            ) {
                HoldingDto(it.getString(1), it.getInt(2), BigDecimal(it.getString(3)).money().toDouble())
            },
            transactions = c.query(
                "SELECT id, type, symbol, quantity, price_cents, date FROM transactions WHERE user_id = ? ORDER BY id",
                userId
            ) {
                TransactionDto(
                    it.getInt(1), it.getString(2), it.getString(3), it.getInt(4),
                    it.getLong(5).toMoney().toDouble(), it.getString(6)
                )
            },
            orders = c.query(
                "SELECT id, symbol, side, quantity, price_cents, fees_cents, status, created_at " +
                        "FROM orders WHERE user_id = ? ORDER BY id DESC",
                userId
            ) {
                OrderDto(
                    it.getInt(1), it.getString(2), it.getString(3), it.getInt(4),
                    it.getLong(5).toMoney().toDouble(), it.getLong(6).toMoney().toDouble(),
                    it.getString(7), it.getString(8)
                )
            }
        )
    }

    // The orders the broker has not answered yet. After a restart, the server answers them.
    fun pendingOrders(): List<Pair<Int, Int>> = Db.transaction { c ->

        c.query("SELECT user_id, id FROM orders WHERE status = 'SUBMITTED'") { it.getInt(1) to it.getInt(2) }
    }

    // ---------- Starting data for the demo user (and for tests) ----------

    fun seedDemo(userId: Int) = Db.transaction { c ->
        cashOf(c, userId)                                           // makes sure the account row exists
        c.execute("DELETE FROM holdings WHERE user_id = ?", userId)
        c.execute("DELETE FROM transactions WHERE user_id = ?", userId)
        c.execute("DELETE FROM orders WHERE user_id = ?", userId)

        setCash(c, userId, BigDecimal("250000.00"))
        addShares(c, userId, "NBM", 300, BigDecimal("1850.00"))
        addShares(c, userId, "TNM", 2000, BigDecimal("23.50"))
        addShares(c, userId, "AIRTEL", 1500, BigDecimal("90.00"))
        addShares(c, userId, "FDHB", 400, BigDecimal("75.00"))

        record(c, userId, "DEPOSIT", null, 0, BigDecimal("1000000.00"), "02 Oct 2026")
        record(c, userId, "BUY", "NBM", 300, BigDecimal("1850.00"), "05 Oct 2026")
        record(c, userId, "BUY", "TNM", 2000, BigDecimal("23.50"), "08 Oct 2026")
        record(c, userId, "BUY", "AIRTEL", 1500, BigDecimal("90.00"), "12 Oct 2026")
        record(c, userId, "BUY", "FDHB", 400, BigDecimal("75.00"), "15 Oct 2026")
        record(c, userId, "SELL", "NBS", 500, BigDecimal("41.00"), "16 Oct 2026")
    }

    // ---------- Wallet ----------

    private fun parseAmount(value: Double): BigDecimal {
        if (!value.isFinite() || value <= 0) throw RuleException("Enter a valid amount")
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP)
    }

    fun deposit(userId: Int, value: Double) {
        val amount = parseAmount(value)
        if (amount < MIN_TRANSFER) throw RuleException("The minimum is MK 1,000")
        if (amount > MAX_DEPOSIT) throw RuleException("The maximum deposit is MK 5,000,000")

        Db.transaction { c ->
            setCash(c, userId, cashOf(c, userId) + amount)
            record(c, userId, "DEPOSIT", null, 0, amount)
        }
    }

    fun withdraw(userId: Int, value: Double) {
        val amount = parseAmount(value)
        if (amount < MIN_TRANSFER) throw RuleException("The minimum is MK 1,000")

        Db.transaction { c ->
            val cash = cashOf(c, userId)
            if (amount > cash) throw RuleException("You only have MK ${text(cash)} available")
            setCash(c, userId, cash - amount)
            record(c, userId, "WITHDRAWAL", null, 0, amount)
        }
    }

    // ---------- Orders ----------

    // Checks the order, RESERVES the money or the shares, and records it as SUBMITTED.
    // Returns the new order's id. Everything happens in ONE transaction.
    fun placeOrder(userId: Int, request: PlaceOrderRequest): Int {
        val side = request.side.uppercase()
        if (side != "BUY" && side != "SELL") throw RuleException("Side must be BUY or SELL")
        if (request.quantity <= 0) throw RuleException("Enter the number of shares")
        if (request.quantity > MAX_ORDER_QUANTITY) throw RuleException("The maximum order is 1,000,000 shares")

        // The PRICE comes from the server's own data. The app can never choose its own price.
        val company = CompanyData.companies.find { it.symbol == request.symbol.uppercase() }
            ?: throw RuleException("Company not found")
        val price = BigDecimal.valueOf(company.price)
        val quantity = request.quantity
        val subtotal = price.multiply(BigDecimal.valueOf(quantity.toLong()))
        val fees = subtotal.multiply(FEE_RATE).money()

        return Db.transaction { c ->
            var costBasis = price
            val cash = cashOf(c, userId)

            if (side == "BUY") {
                val total = (subtotal + fees).money()
                if (total > cash) {
                    throw RuleException("Not enough cash. You need MK ${text(total)} but have MK ${text(cash)}")
                }
                setCash(c, userId, cash - total)                        // RESERVE the money straight away
            } else {
                val position = positionOf(c, userId, company.symbol)
                val owned = position?.quantity ?: 0
                if (quantity > owned) throw RuleException("You only own $owned shares")
                costBasis = position?.avgCost ?: price
                takeShares(c, userId, company.symbol, quantity)          // RESERVE the shares straight away
            }

            c.execute(
                "INSERT INTO orders (user_id, symbol, side, quantity, price_cents, fees_cents, cost_basis, status, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, 'SUBMITTED', ?)",
                userId, company.symbol, side, quantity, price.toCents(), fees.toCents(),
                costBasis.toPlainString(), today()
            )
            c.insertedId().toInt()
        }
    }

    // The simulated broker's answer, a few seconds later. Safe to call twice.
    fun settle(userId: Int, orderId: Int) {
        Db.transaction { c ->
            val order = c.query(
                "SELECT id, symbol, side, quantity, price_cents, fees_cents, cost_basis, status " +
                        "FROM orders WHERE id = ? AND user_id = ?",
                orderId, userId
            ) {
                OrderRow(
                    it.getInt(1), it.getString(2), it.getString(3), it.getInt(4),
                    it.getLong(5).toMoney(), it.getLong(6).toMoney(),
                    BigDecimal(it.getString(7)), it.getString(8)
                )
            }.firstOrNull() ?: return@transaction

            if (order.status != "SUBMITTED") return@transaction          // already settled
            if (order.quantity == REJECTED_QUANTITY) reject(c, userId, order) else fill(c, userId, order)
        }
    }

    private fun fill(c: Connection, userId: Int, order: OrderRow) {
        val subtotal = order.price.multiply(BigDecimal.valueOf(order.quantity.toLong()))
        if (order.side == "BUY") {
            addShares(c, userId, order.symbol, order.quantity, order.price)
        } else {
            setCash(c, userId, cashOf(c, userId) + subtotal - order.fees)
        }
        record(c, userId, order.side, order.symbol, order.quantity, order.price)
        setStatus(c, order.id, "FILLED")
    }

    private fun reject(c: Connection, userId: Int, order: OrderRow) {
        if (order.side == "BUY") {
            val subtotal = order.price.multiply(BigDecimal.valueOf(order.quantity.toLong()))
            setCash(c, userId, cashOf(c, userId) + subtotal + order.fees)     // the money comes back
        } else {
            addShares(c, userId, order.symbol, order.quantity, order.costBasis)   // the shares come back
        }
        setStatus(c, order.id, "REJECTED")
    }
}