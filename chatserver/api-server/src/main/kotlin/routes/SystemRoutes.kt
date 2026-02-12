package routes

import auth.UserPrincipal
import dto.HealthCheckResponse
import dto.SystemInfoResponse
import io.ktor.server.auth.*
import io.ktor.server.plugins.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.systemRoutes(serverId: String) {
    route("/system") {
        get("/health") {
            call.respond(
                HealthCheckResponse(
                    status = "UP",
                    serverId = serverId,
                    timestamp = System.currentTimeMillis()
                )
            )
        }

        authenticate {
            get("/info") {
                val user = call.principal<UserPrincipal>()?.user
                call.respond(
                    SystemInfoResponse(
                        instance = serverId,
                        clientIp = call.request.origin.remoteHost,
                        connectedAs = user?.username,
                        environment = System.getenv("KTOR_ENV") ?: "development"
                    )
                )
            }
        }
    }
}