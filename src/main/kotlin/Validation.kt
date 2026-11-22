package com.example

private val EMAIL_REGEX =
    Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}$")

fun validateName(name: String): String? =
    if (name.trim().length < 2 || name.trim().length > 80) "Enter your full name" else null

fun validateIdentifier(text: String): String? {
    val value = text.trim()
    val isEmail = EMAIL_REGEX.matches(value)
    val isPhone = value.length in 9..10 && value.all { it.isDigit() }
    return if (isEmail || isPhone) null else "Enter a valid email or phone number"
}

// bcrypt only uses the first 72 bytes of a password, so we set a maximum of 64 characters
fun validatePassword(password: String): String? = when {
    password.length < 8 -> "Use at least 8 characters"
    password.length > 64 -> "Use at most 64 characters"
    password.none { it.isLetter() } || password.none { it.isDigit() } ->
        "Include at least one letter and one number"
    else -> null
}

// Returns the first problem found, or null when everything is fine
fun validateRegistration(request: RegisterRequest): String? =
    validateName(request.fullName)
        ?: validateIdentifier(request.identifier)
        ?: validatePassword(request.password)

