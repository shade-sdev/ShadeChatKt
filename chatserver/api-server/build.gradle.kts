plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

group = "com.shade.dev"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":shared"))

    // Ktor server dependencies (ONLY in api-server)
    api(platform("io.ktor:ktor-bom:3.4.0"))
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-netty")
    implementation("io.ktor:ktor-server-websockets")
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-server-auth")
    implementation("io.ktor:ktor-server-auth-jwt")
    implementation("io.ktor:ktor-server-cors")
    implementation("io.ktor:ktor-serialization-kotlinx-json")
    implementation("io.ktor:ktor-server-rate-limit")
}

application {
    mainClass.set("ApplicationKt")
}

kotlin {
    jvmToolchain(21)
}