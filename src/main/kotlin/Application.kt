package com.example

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import io.ktor.server.response.respondText

// ---------- Shapes of the data we send ----------

@Serializable
data class CompanyDto(
    val symbol: String,
    val name: String,
    val sector: String,
    val price: Double,
    val changePct: Double,
    val updatedAt: String,
    val history: List<Double>
)

@Serializable
data class ErrorResponse(val error: String)

@Serializable
data class HealthResponse(val status: String)

// ---------- Company data (a file for now, a database in a later lesson) ----------

object CompanyData {
    private val json = Json { ignoreUnknownKeys = true }

    val companies: List<CompanyDto> by lazy {
        val text = CompanyData::class.java.getResource("/companies.json")?.readText()
            ?: error("companies.json not found in src/main/resources")
        json.decodeFromString(ListSerializer(CompanyDto.serializer()), text)
    }
}

// ---------- Starting the server ----------

fun main() {
    // A cloud host tells the server which port to use through the PORT setting.
    // On your own computer there is no PORT, so we use 8080 and listen only to this computer.
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val host = if (System.getenv("PORT") != null) "0.0.0.0" else "127.0.0.1"

    embeddedServer(Netty, port = port, host = host) {
        module()
    }.start(wait = true)
}

fun Application.module() {

    UserStore.initialize()

    // After a restart, finish whatever was waiting when the server stopped
    AccountStore.pendingOrders().forEach { (userId, orderId) -> Broker.schedule(userId, orderId) }
    KycStore.pendingUserIds().forEach { userId -> KycReviewer.schedule(userId) }

    install(CallLogging)

    install(ContentNegotiation) {
        json(Json { prettyPrint = true; encodeDefaults = true })
    }

    // Checks the token on every route inside authenticate("auth-jwt") { ... }
    install(Authentication) {
        jwt("auth-jwt") {
            realm = "MSE Invest"
            verifier(TokenService.verifier)
            validate { credential ->
                val userId = credential.payload.getClaim("userId").asInt()
                // Accept the token only if its user still exists
                if (userId != null && UserStore.findById(userId) != null) {
                    JWTPrincipal(credential.payload)
                } else {
                    null
                }
            }
        }
    }

    install(StatusPages) {
        exception<RuleException> { call, cause ->
            call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse(cause.message ?: "This is not allowed"))
        }
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("Unhandled error", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Something went wrong on the server"))
        }
    }

    routing {

        get("/") {
            call.respondText("MSE Invest Backend is running")
        }


        get("/health") {
            call.respond(HealthResponse("ok"))
        }

        route("/api/v1") {

            // Registration, login and /me
            authRoutes()
            // Wallet, orders and the account snapshot
            accountRoutes()
            // Registration and the administrators' review
            kycRoutes()

            // GET /api/v1/companies  or  /api/v1/companies?sector=Banking
            get("/companies") {
                val sector = call.request.queryParameters["sector"]
                val list = CompanyData.companies.filter {
                    sector == null || it.sector.equals(sector, ignoreCase = true)
                }
                call.respond(list)
            }

            // GET /api/v1/companies/NBM
            get("/companies/{symbol}") {
                val symbol = call.parameters["symbol"]?.uppercase()
                val company = CompanyData.companies.find { it.symbol == symbol }
                if (company == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Company not found"))
                } else {
                    call.respond(company)
                }
            }
        }
    }
}