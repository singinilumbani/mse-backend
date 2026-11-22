package com.example


import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// Tests for the rules that now live on the server. Test names start with the
// Test Case IDs of the "MSE Invest Test Case Design" document.
class AccountStoreTest {

    companion object {
        private var counter = 0       // every test gets a brand-new user, so tests never affect each other
    }

    // A real user in the database
    private fun newUser(): Int {
        counter += 1
        return UserStore.register("Test User", "acc$counter@mse.test", "Sample123")!!.id
    }

    private fun demoLikeUser(): Int {
        val id = newUser()
        AccountStore.seedDemo(id)    // cash MK 250,000.00 and the four starting holdings
        return id
    }

    private fun order(side: String, quantity: Int, symbol: String = "NBM") =
        PlaceOrderRequest(symbol, side, quantity)

    @Test
    fun TRD_BUY_001_validBuy_reservesMoney_thenFillsIntoHoldings() {
        val user = demoLikeUser()
        val id = AccountStore.placeOrder(user, order("BUY", 10))

        assertEquals(229_498.00, AccountStore.snapshot(user).cash, 0.001)   // reserved at once

        AccountStore.settle(user, id)
        val account = AccountStore.snapshot(user)
        val nbm = account.holdings.first { it.symbol == "NBM" }

        assertEquals(310, nbm.quantity)
        assertEquals(1855.16, nbm.avgCost, 0.001)
        assertEquals("FILLED", account.orders.first { it.id == id }.status)
    }

    @Test
    fun TRD_BUY_002_moreThanTheCash_isRefusedWithTheExactAmount() {
        val user = demoLikeUser()
        val error = assertFailsWith<RuleException> { AccountStore.placeOrder(user, order("BUY", 1000)) }

        assertEquals("Not enough cash. You need MK 2,050,200.00 but have MK 250,000.00", error.message)
    }

    @Test
    fun TRD_BUY_004_rejectedOrder_returnsAllTheMoney() {
        val user = demoLikeUser()
        val id = AccountStore.placeOrder(user, order("BUY", 13))
        assertEquals(223_347.40, AccountStore.snapshot(user).cash, 0.001)    // 250,000 - 26,652.60

        AccountStore.settle(user, id)
        val account = AccountStore.snapshot(user)

        assertEquals("REJECTED", account.orders.first { it.id == id }.status)
        assertEquals(250_000.00, account.cash, 0.001)
    }

    @Test
    fun TRD_BUY_006_121Shares_isAllowed() {
        val user = demoLikeUser()
        AccountStore.placeOrder(user, order("BUY", 121))

        assertEquals(1_925.80, AccountStore.snapshot(user).cash, 0.001)   // 250,000 - 248,074.20
    }

    @Test
    fun TRD_BUY_007_122Shares_isRefused() {
        val user = demoLikeUser()
        val error = assertFailsWith<RuleException> { AccountStore.placeOrder(user, order("BUY", 122)) }

        assertEquals("Not enough cash. You need MK 250,124.40 but have MK 250,000.00", error.message)
    }

    @Test
    fun TRD_SELL_001_fiveShares_youReceiveSubtotalMinusFees() {
        val user = demoLikeUser()
        val id = AccountStore.placeOrder(user, order("SELL", 5))
        AccountStore.settle(user, id)
        val account = AccountStore.snapshot(user)

        assertEquals(259_849.00, account.cash, 0.001)                    // + 10,050.00 - 201.00
        assertEquals(295, account.holdings.first { it.symbol == "NBM" }.quantity)
    }

    @Test
    fun TRD_SELL_002_301SharesWhen300Owned_isRefused() {
        val user = demoLikeUser()
        val error = assertFailsWith<RuleException> { AccountStore.placeOrder(user, order("SELL", 301)) }

        assertEquals("You only own 300 shares", error.message)
    }

    @Test
    fun TRD_SELL_003_companyNotOwned_isRefused() {
        val user = demoLikeUser()
        val error = assertFailsWith<RuleException> { AccountStore.placeOrder(user, order("SELL", 1, "OMU")) }

        assertEquals("You only own 0 shares", error.message)
    }

    @Test
    fun TRD_SELL_004_allShares_removesTheHolding() {
        val user = demoLikeUser()
        val id = AccountStore.placeOrder(user, order("SELL", 400, "FDHB"))
        AccountStore.settle(user, id)
        val account = AccountStore.snapshot(user)

        assertTrue(account.holdings.none { it.symbol == "FDHB" })
        assertEquals(280_772.00, account.cash, 0.001)                    // + 31,400.00 - 628.00
    }

    @Test
    fun TRD_SELL_005_rejectedSell_returnsTheShares() {
        val user = demoLikeUser()
        val id = AccountStore.placeOrder(user, order("SELL", 13, "FDHB"))
        AccountStore.settle(user, id)

        assertEquals(400, AccountStore.snapshot(user).holdings.first { it.symbol == "FDHB" }.quantity)
    }

    @Test
    fun WAL_DEPOSIT_002_below1000_isRefused() {
        val user = demoLikeUser()
        val error = assertFailsWith<RuleException> { AccountStore.deposit(user, 500.0) }

        assertEquals("The minimum is MK 1,000", error.message)
    }

    @Test
    fun WAL_DEPOSIT_003_exactly1000_isAccepted() {
        val user = demoLikeUser()
        AccountStore.deposit(user, 1_000.0)

        assertEquals(251_000.00, AccountStore.snapshot(user).cash, 0.001)
    }

    @Test
    fun WAL_WITHDRAW_002_moreThanTheBalance_isRefused() {
        val user = demoLikeUser()
        val error = assertFailsWith<RuleException> { AccountStore.withdraw(user, 250_001.0) }

        assertEquals("You only have MK 250,000.00 available", error.message)
    }

    @Test
    fun WAL_WITHDRAW_003_exactlyTheBalance_isAccepted() {
        val user = demoLikeUser()
        AccountStore.withdraw(user, 250_000.0)

        assertEquals(0.0, AccountStore.snapshot(user).cash, 0.001)
    }

    @Test
    fun SEC_PRIVACY_002_usersNeverShareData() {
        val first = demoLikeUser()
        val second = newUser()                       // a new user with no data at all
        AccountStore.placeOrder(first, order("BUY", 10))

        val other = AccountStore.snapshot(second)
        assertEquals(0.0, other.cash, 0.0)
        assertTrue(other.holdings.isEmpty())
        assertTrue(other.orders.isEmpty())
        assertTrue(other.transactions.isEmpty())
    }

    @Test
    fun ACC_ORDER_001_priceComesFromTheServer() {
        val user = demoLikeUser()
        AccountStore.placeOrder(user, order("BUY", 1))

        // PlaceOrderRequest has no price field, so the server's price must be used
        assertEquals(2010.0, AccountStore.snapshot(user).orders.first().price, 0.0)
    }
}