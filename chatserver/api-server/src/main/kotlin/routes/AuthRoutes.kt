package routes

import auth.JWTConfig
import service.UserService
import dto.AuthResponse
import dto.ErrorResponse
import dto.LoginRequest
import dto.RegisterRequest
import validation.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.authRoutes(userService: UserService) {
    route("/auth") {
        rateLimit(RateLimitName("auth")) {

            post("/register") {
                val request = call.receive<RegisterRequest>()

                val validation = validateRegisterRequest(request)
                if (validation.errors.isNotEmpty()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(
                            error = "Validation failed",
                            details = validation.errors.map { it.message }
                        )
                    )
                    return@post
                }

                val user = userService.register(
                    username = request.username,
                    password = request.password,
                    displayName = sanitizeInput(request.displayName)
                )

                if (user == null) {
                    call.respond(
                        HttpStatusCode.Conflict,
                        ErrorResponse(error = "Username already exists")
                    )
                    return@post
                }

                val token = JWTConfig.generateToken(user.id)
                call.respond(AuthResponse(token, userService.toResponse(user)))
            }

            post("/login") {
                val request = call.receive<LoginRequest>()

                if (request.username.isBlank() || request.password.isBlank()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(error = "Username and password required")
                    )
                    return@post
                }

                val user = userService.authenticate(request.username, request.password)
                if (user == null) {
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        ErrorResponse(error = "Invalid credentials")
                    )
                    return@post
                }

                val token = JWTConfig.generateToken(user.id)
                call.respond(AuthResponse(token, userService.toResponse(user)))
            }
        }
    }
}