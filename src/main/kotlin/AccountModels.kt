package com.example

import kotlinx.serialization.Serializable

// ---------- What the server ANSWERS ----------
// Money leaves the server as a number with 2 decimals. Inside the server it is always BigDecimal.

@Serializable
data class HoldingDto(val symbol: String, val quantity: Int, val avgCost: Double)

// For a deposit or withdrawal, "price" holds the amount of money and quantity is 0
@Serializable
data class TransactionDto(
    val id: Int,
    val type: String,          // BUY, SELL, DEPOSIT or WITHDRAWAL
    val symbol: String?,
    val quantity: Int,
    val price: Double,
    val date: String
)

@Serializable
data class OrderDto(
    val id: Int,
    val symbol: String,
    val side: String,          // BUY or SELL
    val quantity: Int,
    val price: Double,
    val fees: Double,
    val status: String,        // SUBMITTED, FILLED or REJECTED
    val createdAt: String
)

@Serializable
data class AccountDto(
    val cash: Double,
    val holdings: List<HoldingDto>,
    val transactions: List<TransactionDto>,
    val orders: List<OrderDto>
)

@Serializable
data class PlaceOrderResponse(val orderId: Int, val account: AccountDto)

// ---------- What the app SENDS ----------
// Notice what is missing: there is NO price and NO total. The server works those out.

@Serializable
data class PlaceOrderRequest(val symbol: String, val side: String, val quantity: Int)

@Serializable
data class AmountRequest(val amount: Double)

