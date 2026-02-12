plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
rootProject.name = "chatserver"

include("shared")
include("shared")
include("api-server")
include("worker")