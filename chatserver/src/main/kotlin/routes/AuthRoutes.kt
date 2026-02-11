package routes

import auth.JWTConfig
import service.UserService
import dto.AuthResponse
import dto.LoginRequest
import dto.RegisterRequest
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.authRoutes(userService: UserService) {
    route("/auth") {
        post("/register") {
            val request = call.receive<RegisterRequest>()

            val user = userService.register(request.username, request.password, request.displayName)
            if (user == null) {
                call.respond(HttpStatusCode.Conflict, mapOf("error" to "Username already exists"))
                return@post
            }

            val token = JWTConfig.generateToken(user.id)
            call.respond(AuthResponse(token, userService.toResponse(user)))
        }

        post("/login") {
            val request = call.receive<LoginRequest>()

            val user = userService.authenticate(request.username, request.password)
            if (user == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid credentials"))
                return@post
            }

            val token = JWTConfig.generateToken(user.id)
            call.respond(AuthResponse(token, userService.toResponse(user)))
        }
    }
}