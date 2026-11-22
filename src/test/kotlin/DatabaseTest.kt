package com.example

import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Tests for the guarantees the database gives. Names starting with a Test Case ID come from the
// "MSE Invest Test Case Design" document.
class DatabaseTest {

    companion object {
        private var counter = 0
    }

    private fun newUser(): Int {
        counter += 1
        return UserStore.register("Db Test", "db$counter@mse.test", "Sample123")!!.id
    }

    @Test
    fun users_survive_andAreFoundAgain() {
        val id = newUser()

        val found = UserStore.findById(id)
        assertEquals("db$counter@mse.test", found?.identifier)
        assertEquals("INVESTOR", found?.role)
    }

    @Test
    fun AUTH_SIGNUP_008_duplicateEmail_isRefused_andTheDatabaseAgrees() {
        newUser()
        assertNull(UserStore.register("Copy", "db$counter@mse.test", "Sample123"))

        // Even if the code forgot to check, the UNIQUE rule in the database would stop it
        assertFailsWith<SQLException> {
            Db.transaction { c ->
                c.execute(
                    "INSERT INTO users (full_name, identifier, password_hash, role) VALUES ('X', ?, 'h', 'INVESTOR')",
                    "db$counter@mse.test"
                )
            }
        }
    }

    @Test
    fun negativeCash_isRefusedByTheDatabaseItself() {
        val id = newUser()
        AccountStore.deposit(id, 1_000.0)

        assertFailsWith<SQLException> {
            Db.transaction { c -> c.execute("UPDATE accounts SET cash_cents = -1 WHERE user_id = ?", id) }
        }
        assertEquals(1_000.0, AccountStore.snapshot(id).cash, 0.0)
    }

    @Test
    fun dataForAUserWhoDoesNotExist_isRefused() {
        assertFailsWith<SQLException> {
            Db.transaction { c ->
                c.execute("INSERT INTO holdings (user_id, symbol, quantity, avg_cost) VALUES (999999, 'NBM', 1, '1')")
            }
        }
    }

    @Test
    fun aTransaction_isAllOrNothing() {
        val id = newUser()
        AccountStore.deposit(id, 5_000.0)

        // Change the cash, then fail: the change must be undone
        assertFailsWith<RuleException> {
            Db.transaction { c ->
                c.execute("UPDATE accounts SET cash_cents = 0 WHERE user_id = ?", id)
                throw RuleException("something went wrong halfway")
            }
        }
        assertEquals(5_000.0, AccountStore.snapshot(id).cash, 0.0)
    }

    @Test
    fun ACC_BALANCE_moneyAddsUpExactly_withNoRoundingDrift() {
        val id = newUser()
        AccountStore.deposit(id, 1_000.10)
        AccountStore.deposit(id, 1_000.20)
        AccountStore.deposit(id, 1_000.30)

        // With Double arithmetic, 1000.1 + 1000.2 + 1000.3 gives 3000.6000000000004
        assertEquals(3_000.60, AccountStore.snapshot(id).cash, 0.0)
    }

    @Test
    fun SEC_sqlInjection_isImpossible() {
        // This text would match EVERY user if it were glued into the SQL
        assertNull(UserStore.findByIdentifier("' OR '1'='1"))
    }

    @Test
    fun pendingOrders_listsOrdersWaitingForTheBroker() {
        val id = newUser()
        AccountStore.deposit(id, 100_000.0)
        val orderId = AccountStore.placeOrder(id, PlaceOrderRequest("NBM", "BUY", 1))

        assertTrue(AccountStore.pendingOrders().contains(id to orderId))

        AccountStore.settle(id, orderId)
        assertTrue(!AccountStore.pendingOrders().contains(id to orderId))
    }

    @Test
    fun settlingTwice_changesNothingTheSecondTime() {
        val id = newUser()
        AccountStore.deposit(id, 100_000.0)
        val orderId = AccountStore.placeOrder(id, PlaceOrderRequest("NBM", "BUY", 1))

        AccountStore.settle(id, orderId)
        val afterFirst = AccountStore.snapshot(id)
        AccountStore.settle(id, orderId)

        assertEquals(afterFirst.cash, AccountStore.snapshot(id).cash, 0.0)
        assertEquals(1, AccountStore.snapshot(id).holdings.first { it.symbol == "NBM" }.quantity)
    }
}

