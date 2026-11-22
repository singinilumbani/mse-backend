package com.example

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlin.coroutines.cancellation.CancellationException

// Used when the email does not exist, so logging in with an unknown email
// takes as long as a real one. Otherwise the time would reveal which emails have accounts.
private val dummyHash = PasswordHasher.hash("not-a-real-password")

// Reads the JSON body. If it is missing or broken, answers 400 and returns null.
internal suspend inline fun <reified T : Any> ApplicationCall.receiveJson(): T? =
    try {
        receive<T>()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
        null
    }

fun Route.authRoutes() {

    route("/auth") {

        // POST /api/v1/auth/register
        post("/register") {
            val request = call.receiveJson<RegisterRequest>() ?: return@post

            // NEVER trust the app. Check everything again here.
            val problem = validateRegistration(request)
            if (problem != null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(problem))
                return@post
            }

            val user = UserStore.register(request.fullName, request.identifier, request.password)
            if (user == null) {
                call.respond(
                    HttpStatusCode.Conflict,
                    ErrorResponse("An account with this email or number already exists")
                )
                return@post
            }

            call.respond(HttpStatusCode.Created, AuthResponse(TokenService.create(user), user.toDto()))
        }

        // POST /api/v1/auth/login
        post("/login") {
            val request = call.receiveJson<LoginRequest>() ?: return@post

            val user = UserStore.findByIdentifier(request.identifier)

            // Always run the check, even for an unknown email
            val passwordOk = PasswordHasher.matches(request.password, user?.passwordHash ?: dummyHash)

            if (user == null || !passwordOk) {
                // The SAME message for "no such email" and "wrong password"
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Wrong email, number or password"))
                return@post
            }

            call.respond(AuthResponse(TokenService.create(user), user.toDto()))
        }
    }

    // Everything inside this block needs a valid token
    authenticate("auth-jwt") {

        // GET /api/v1/me
        get("/me") {
            val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asInt()
            val user = userId?.let { UserStore.findById(it) }

            if (user == null) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Please log in again"))
            } else {
                call.respond(user.toDto())
            }
        }
    }
}

