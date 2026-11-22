
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(ktorLibs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
}

group = "com.example"
version = "1.0.0-SNAPSHOT"

application {
    mainClass = "com.example.ApplicationKt"
}

kotlin {
    jvmToolchain(21)
}
dependencies {
    implementation(ktorLibs.serialization.kotlinx.json)
    implementation(ktorLibs.server.callLogging)
    implementation(ktorLibs.server.contentNegotiation)
    implementation(ktorLibs.server.core)
    implementation(ktorLibs.server.netty)
    implementation(ktorLibs.server.statusPages)
    implementation(libs.logback.classic)

    testImplementation(kotlin("test"))
    testImplementation(ktorLibs.server.testHost)
    implementation("io.ktor:ktor-server-auth:X.Y.Z")
    implementation("io.ktor:ktor-server-auth-jwt:X.Y.Z")
    implementation("at.favre.lib:bcrypt:0.10.2")
    implementation("org.xerial:sqlite-jdbc:3.51.2.0")
}

// Unit tests use a throw-away database in memory, so they never touch your real mse.db file
tasks.withType<Test> {
    systemProperty("mse.db", ":memory:")
}
