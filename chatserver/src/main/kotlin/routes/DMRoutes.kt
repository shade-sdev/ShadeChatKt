package routes

import auth.UserPrincipal
import service.DMService
import service.MessageService
import dto.CreateDMRequest
import dto.SendMessageRequest
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
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

            post("/{id}/messages") {
                val user = call.principal<UserPrincipal>()?.user
                    ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val dmId = call.parameters["id"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing DM ID"))
                    return@post
                }
                val request = call.receive<SendMessageRequest>()

                val dm = dmService.findById(dmId)
                if (dm == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "DM not found"))
                    return@post
                }

                if (user.id != dm.participant1Id && user.id != dm.participant2Id) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Not a participant"))
                    return@post
                }

                val message = messageService.sendMessage(user.id, null, dmId, request.content)
                call.respond(HttpStatusCode.Created, mapOf("id" to message.id))
            }

            get("/{id}/messages") {
                val user = call.principal<UserPrincipal>()?.user
                    ?: return@get call.respond(HttpStatusCode.Unauthorized)
                val dmId = call.parameters["id"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing DM ID"))
                    return@get
                }
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0

                val dm = dmService.findById(dmId)
                if (dm == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "DM not found"))
                    return@get
                }

                if (user.id != dm.participant1Id && user.id != dm.participant2Id) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Not a participant"))
                    return@get
                }

                val messages = messageService.getDMMessages(dmId, limit, offset)
                call.respond(messages)
            }
        }
    }
}