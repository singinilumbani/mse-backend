package com.example



import kotlinx.serialization.Serializable

// ---------- What the app SENDS ----------

@Serializable
data class RegisterRequest(val fullName: String, val identifier: String, val password: String)

@Serializable
data class LoginRequest(val identifier: String, val password: String)

// ---------- What the server ANSWERS ----------

@Serializable
data class UserDto(val id: Int, val fullName: String, val identifier: String)

@Serializable
data class AuthResponse(val token: String, val user: UserDto)

// ---------- Kept on the server only ----------
// It holds the password hash, so it is NOT @Serializable. It can never be sent by accident.
data class User(
    val id: Int,
    val fullName: String,
    val identifier: String,     // an email or a phone number, in lower case
    val passwordHash: String,
    val role: String = "INVESTOR"   // INVESTOR or ADMIN
) {
    fun toDto() = UserDto(id, fullName, identifier)
}