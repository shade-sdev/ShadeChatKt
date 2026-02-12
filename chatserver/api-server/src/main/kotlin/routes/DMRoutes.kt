package routes

import auth.UserPrincipal
import service.DMService
import service.MessageService
import dto.CreateDMRequest
import dto.ErrorResponse
import dto.MessageIdResponse
import dto.SendMessageRequest
import validation.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.dmRoutes(dmService: DMService, messageService: MessageService) {
    route("/dms") {
        authenticate {
            post {
                val user = call.principal<UserPrincipal>()?.user
                    ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val request = call.receive<CreateDMRequest>()

                val dm = dmService.createOrGetConversation(user.id, request.recipientId)
                call.respond(HttpStatusCode.Created, dmService.toResponse(dm, user.id))
            }

            get {
                val user = call.principal<UserPrincipal>()?.user
                    ?: return@get call.respond(HttpStatusCode.Unauthorized)
                val conversations = dmService.findByUserId(user.id)
                val responses = conversations.map { dmService.toResponse(it, user.id) }
                call.respond(responses)
            }

            rateLimit(RateLimitName("write")) {
                post("/{id}/messages") {
                    val user = call.principal<UserPrincipal>()?.user
                        ?: return@post call.respond(HttpStatusCode.Unauthorized)
                    val dmId = call.parameters["id"] ?: run {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing DM ID"))
                        return@post
                    }
                    val request = call.receive<SendMessageRequest>()

                    val validation = validateSendMessageRequest(request)
                    if (validation.errors.isNotEmpty()) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(
                            error = "Validation failed",
                            details = validation.errors.map { it.message }
                        ))
                        return@post
                    }

                    val dm = dmService.findById(dmId)
                    if (dm == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("DM not found"))
                        return@post
                    }

                    if (user.id != dm.participant1Id && user.id != dm.participant2Id) {
                        call.respond(HttpStatusCode.Forbidden, ErrorResponse("Not a participant"))
                        return@post
                    }

                    val message = messageService.sendMessage(
                        user.id,
                        null,
                        dmId,
                        sanitizeInput(request.content)
                    )
                    call.respond(HttpStatusCode.Created, MessageIdResponse(id = message.id))
                }
            }

            get("/{id}/messages") {
                val user = call.principal<UserPrincipal>()?.user
                    ?: return@get call.respond(HttpStatusCode.Unauthorized)
                val dmId = call.parameters["id"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing DM ID"))
                    return@get
                }

                val cursor = call.request.queryParameters["cursor"]
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 50

                val dm = dmService.findById(dmId)
                if (dm == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("DM not found"))
                    return@get
                }

                if (user.id != dm.participant1Id && user.id != dm.participant2Id) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Not a participant"))
                    return@get
                }

                val messages = messageService.getDMMessages(dmId, cursor, limit)
                call.respond(messages)
            }
        }
    }
}