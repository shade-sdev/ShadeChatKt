plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

group = "com.shade.dev"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}


dependencies {
    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")

    // DateTime
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1-0.6.x-compat")

    // Redis (both API server and worker need this)
    implementation("io.lettuce:lettuce-core:7.4.0.RELEASE")

    // Coroutines (both need this)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // BCrypt password hashing
    implementation("org.mindrot:jbcrypt:0.4")

    // Input validation
    api("io.konform:konform:0.11.1")

    // JWT
    implementation("com.auth0:java-jwt:4.5.0")

    // Database (both need this)
    implementation("org.jetbrains.exposed:exposed-core:0.55.0")
    implementation("org.jetbrains.exposed:exposed-dao:0.55.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.55.0")
    implementation("org.jetbrains.exposed:exposed-java-time:0.55.0")
    implementation("org.jetbrains.exposed:exposed-kotlin-datetime:0.55.0")
    implementation("org.postgresql:postgresql:42.7.9")
    implementation("com.zaxxer:HikariCP:6.0.0")

    // Flyway (both need this)
    implementation("org.flywaydb:flyway-core:12.0.0")
    implementation("org.flywaydb:flyway-database-postgresql:12.0.0")

    // Logging
    api("ch.qos.logback:logback-classic:1.5.29")
}

kotlin {
    jvmToolchain(21)
}