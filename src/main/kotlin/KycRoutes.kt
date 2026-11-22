package com.example

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

// Like requireUser, but the user must also be an administrator.
// The role is read from the server's own user list, never from the app.
private suspend fun ApplicationCall.requireAdmin(): Int? {
    val userId = requireUser() ?: return null
    if (UserStore.findById(userId)?.role != "ADMIN") {
        respond(HttpStatusCode.Forbidden, ErrorResponse("This action is for administrators only"))
        return null
    }
    return userId
}

fun Route.kycRoutes() {

    authenticate("auth-jwt") {

        // ---------- The user's own registration ----------
        route("/kyc") {

            // GET /api/v1/kyc -> status and form
            get {
                val userId = call.requireUser() ?: return@get
                call.respond(KycStore.get(userId))
            }

            // PUT /api/v1/kyc/draft -> save the form so far
            put("/draft") {
                val userId = call.requireUser() ?: return@put
                val draft = call.receiveJson<KycDraftDto>() ?: return@put
                call.respond(KycStore.saveDraft(userId, draft))
            }

            // POST /api/v1/kyc/submit -> send it for review
            post("/submit") {
                val userId = call.requireUser() ?: return@post
                val result = KycStore.submit(userId)        // throws RuleException when something is wrong
                KycReviewer.schedule(userId)
                call.respond(result)
            }
        }

        // ---------- Administrators only ----------
        route("/admin/kyc") {

            // GET /api/v1/admin/kyc -> registrations waiting for a person to decide
            get {
                call.requireAdmin() ?: return@get
                call.respond(KycStore.pendingReviews())
            }

            // POST /api/v1/admin/kyc/7/approve
            post("/{userId}/approve") {
                call.requireAdmin() ?: return@post
                val target = call.parameters["userId"]?.toIntOrNull()
                if (target == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid user id"))
                    return@post
                }
                KycStore.approve(target)
                call.respond(KycDecisionResponse(target, KycStore.status(target)))
            }

            // POST /api/v1/admin/kyc/7/reject   { "reason": "Photo is unclear" }
            post("/{userId}/reject") {
                call.requireAdmin() ?: return@post
                val target = call.parameters["userId"]?.toIntOrNull()
                if (target == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid user id"))
                    return@post
                }
                val request = call.receiveJson<RejectRequest>() ?: return@post
                KycStore.reject(target, request.reason)
                call.respond(KycDecisionResponse(target, KycStore.status(target)))
            }
        }
    }
}

