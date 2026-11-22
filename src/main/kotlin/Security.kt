package com.example

import at.favre.lib.crypto.bcrypt.BCrypt
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.util.Date

object PasswordHasher {
    // Every +1 doubles the work. Slow on purpose: it makes guessing passwords expensive.
    private const val COST = 12

    // Turns "Sample123" into something like "$2a$12$..." with a fresh random salt each time
    fun hash(password: String): String =
        BCrypt.withDefaults().hashToString(COST, password.toCharArray())

    // Does this password match this hash?
    fun matches(password: String, hash: String): Boolean {
        return BCrypt.verifyer().verify(password.toCharArray(), hash).verified
    }
}

object TokenService {
    private const val ISSUER = "mse-invest"
    private const val AUDIENCE = "mse-invest-app"
    private const val LIFETIME_MS = 60 * 60 * 1000L   // a token lasts one hour

    // The secret comes from the environment, never from the code. See Step 8.
    // The server is "in the cloud" when its host gives it a PORT. Then a strong secret is REQUIRED.
    private val inCloud = System.getenv("PORT") != null

    private val secret: String = System.getenv("JWT_SECRET") ?: run {
        if (inCloud) error("JWT_SECRET must be set when the server runs in the cloud")
        println("WARNING: JWT_SECRET is not set. Using an insecure development secret.")
        "dev-only-secret-change-me-0123456789"
    }

    init {
        if (inCloud && secret.length < 32) error("JWT_SECRET must be at least 32 characters long")
    }
    private val algorithm = Algorithm.HMAC256(secret)

    // Used by the Authentication plugin to check every incoming token
    val verifier = JWT.require(algorithm)
        .withIssuer(ISSUER)
        .withAudience(AUDIENCE)
        .build()

    fun create(user: User): String = JWT.create()
        .withIssuer(ISSUER)
        .withAudience(AUDIENCE)
        .withClaim("userId", user.id)
        .withClaim("role", user.role)
        .withExpiresAt(Date(System.currentTimeMillis() + LIFETIME_MS))
        .sign(algorithm)
}

