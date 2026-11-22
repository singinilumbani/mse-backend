package com.example

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

// Who is asking? From the signed token, never from anything the app claims.
// Answers 401 and returns null when there is no valid user.
internal suspend fun ApplicationCall.requireUser(): Int? {
    val userId = principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asInt()
    if (userId == null || UserStore.findById(userId) == null) {
        respond(HttpStatusCode.Unauthorized, ErrorResponse("Please log in again"))
        return null
    }
    return userId
}

fun Route.accountRoutes() {

    // Every route in here needs a valid token
    authenticate("auth-jwt") {

        // GET /api/v1/account -> everything about the logged-in user's money
        get("/account") {
            val userId = call.requireUser() ?: return@get
            call.respond(AccountStore.snapshot(userId))
        }

        // POST /api/v1/orders  { "symbol": "NBM", "side": "BUY", "quantity": 10 }
        post("/orders") {
            val userId = call.requireUser() ?: return@post
            // The server, not the app, decides who may trade
            if (!KycStore.isApproved(userId)) {
                call.respond(
                    HttpStatusCode.Forbidden,
                    ErrorResponse("Your registration must be approved before you can trade")
                )
                return@post
            }
            val request = call.receiveJson<PlaceOrderRequest>() ?: return@post

            val orderId = AccountStore.placeOrder(userId, request)   // throws RuleException when a rule fails
            Broker.schedule(userId, orderId)

            call.respond(HttpStatusCode.Created, PlaceOrderResponse(orderId, AccountStore.snapshot(userId)))
        }

        route("/wallet") {

            // POST /api/v1/wallet/deposit  { "amount": 10000 }
            post("/deposit") {
                val userId = call.requireUser() ?: return@post
                val request = call.receiveJson<AmountRequest>() ?: return@post
                AccountStore.deposit(userId, request.amount)
                call.respond(AccountStore.snapshot(userId))
            }

            // POST /api/v1/wallet/withdraw  { "amount": 20000 }
            post("/withdraw") {
                val userId = call.requireUser() ?: return@post
                val request = call.receiveJson<AmountRequest>() ?: return@post
                AccountStore.withdraw(userId, request.amount)
                call.respond(AccountStore.snapshot(userId))
            }
        }
    }
}

