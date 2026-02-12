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

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}

application {
    mainClass.set("WorkerKt")
}

kotlin {
    jvmToolchain(21)
}