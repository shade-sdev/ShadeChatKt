plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "1.9.22"
    application
}

group = "com.shade.dev"
version = "1.0-SNAPSHOT"

allprojects {
    group = "com.shade.dev"
    version = "1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

kotlin {
    jvmToolchain(21)
}
