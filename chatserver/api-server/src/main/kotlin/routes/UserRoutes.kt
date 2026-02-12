package routes

import auth.UserPrincipal
import dto.ErrorResponse
import dto.UpdateUserRequest
import validation.*
import service.UserService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.userRoutes(userService: UserService) {
    route("/users") {
        authenticate {
            get("/me") {
                val user = call.principal<UserPrincipal>()?.user
                    ?: return@get call.respond(HttpStatusCode.Unauthorized)
                call.respond(userService.toResponse(user))
            }

            get("/all") {
                val user = call.principal<UserPrincipal>()?.user
                    ?: return@get call.respond(HttpStatusCode.Unauthorized)

                val allUsers = userService.getAllUsers()
                call.respond(allUsers.map { userService.toResponse(it) })
            }

            put("/me") {
                val user = call.principal<UserPrincipal>()?.user
                    ?: return@put call.respond(HttpStatusCode.Unauthorized)
                val request = call.receive<UpdateUserRequest>()

                val validation = validateUpdateUserRequest(request)
                if (validation.errors.isNotEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(
                        error = "Validation failed",
                        details = validation.errors.map { it.message }
                    ))
                    return@put
                }

                val updated = userService.updateUser(
                    user.id,
                    request.displayName?.let { sanitizeInput(it) },
                    request.avatarUrl
                )
                if (updated == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))
                    return@put
                }

                call.respond(userService.toResponse(updated))
            }

            get("/{id}") {
                val userId = call.parameters["id"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing user ID"))
                    return@get
                }

                val user = userService.findById(userId)
                if (user == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))
                    return@get
                }

                call.respond(userService.toResponse(user))
            }
        }
    }
}