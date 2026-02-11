package routes

import auth.UserPrincipal
import service.GroupService
import dto.AddMemberRequest
import dto.CreateGroupRequest
import dto.SendMessageRequest
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import service.MessageService

fun Route.groupRoutes(groupService: GroupService, messageService: MessageService) {
    route("/groups") {
        authenticate {
            post {
                val user = call.principal<UserPrincipal>()?.user
                    ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val request = call.receive<CreateGroupRequest>()

                val group = groupService.createGroup(
                    request.name,
                    request.description,
                    user.id,
                    request.memberIds
                )

                call.respond(HttpStatusCode.Created, groupService.toResponse(group))
            }

            post("/{id}/messages") {
                val user = call.principal<UserPrincipal>()?.user
                    ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val groupId = call.parameters["id"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing group ID"))
                    return@post
                }
                val request = call.receive<SendMessageRequest>()

                val group = groupService.findById(groupId)
                if (group == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Group not found"))
                    return@post
                }

                if (user.id !in group.memberIds) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Not a member"))
                    return@post
                }

                val message = messageService.sendMessage(user.id, groupId, null, request.content)
                call.respond(HttpStatusCode.Created, mapOf("id" to message.id))
            }

            get("/{id}/messages") {
                val user = call.principal<UserPrincipal>()?.user
                    ?: return@get call.respond(HttpStatusCode.Unauthorized)
                val groupId = call.parameters["id"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing group ID"))
                    return@get
                }
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0

                val group = groupService.findById(groupId)
                if (group == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Group not found"))
                    return@get
                }

                if (user.id !in group.memberIds) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Not a member"))
                    return@get
                }

                val messages = messageService.getGroupMessages(groupId, limit, offset)
                call.respond(messages)
            }

            get {
                val user = call.principal<UserPrincipal>()?.user
                    ?: return@get call.respond(HttpStatusCode.Unauthorized)
                val groups = groupService.findByUserId(user.id)
                call.respond(groups.map { groupService.toResponse(it) })
            }

            get("/{id}") {
                val user = call.principal<UserPrincipal>()?.user
                    ?: return@get call.respond(HttpStatusCode.Unauthorized)
                val groupId = call.parameters["id"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing group ID"))
                    return@get
                }

                val group = groupService.findById(groupId)
                if (group == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Group not found"))
                    return@get
                }

                if (user.id !in group.memberIds) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Not a member"))
                    return@get
                }

                call.respond(groupService.toResponse(group))
            }

            delete("/{id}") {
                val user = call.principal<UserPrincipal>()?.user
                    ?: return@delete call.respond(HttpStatusCode.Unauthorized)
                val groupId = call.parameters["id"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing group ID"))
                    return@delete
                }

                val group = groupService.findById(groupId)
                if (group == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Group not found"))
                    return@delete
                }

                if (group.adminId != user.id) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Only admin can delete group"))
                    return@delete
                }

                groupService.deleteGroup(groupId)
                call.respond(HttpStatusCode.NoContent)
            }

            post("/{id}/members") {
                val user = call.principal<UserPrincipal>()?.user
                    ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val groupId = call.parameters["id"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing group ID"))
                    return@post
                }
                val request = call.receive<AddMemberRequest>()

                val group = groupService.findById(groupId)
                if (group == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Group not found"))
                    return@post
                }

                if (group.adminId != user.id) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Only admin can add members"))
                    return@post
                }

                val updated = groupService.addMember(groupId, request.userId)
                call.respond(groupService.toResponse(updated!!))
            }

            delete("/{id}/members/{userId}") {
                val user = call.principal<UserPrincipal>()?.user
                    ?: return@delete call.respond(HttpStatusCode.Unauthorized)
                val groupId = call.parameters["id"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing group ID"))
                    return@delete
                }
                val targetUserId = call.parameters["userId"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing user ID"))
                    return@delete
                }

                val group = groupService.findById(groupId)
                if (group == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Group not found"))
                    return@delete
                }

                if (group.adminId != user.id && targetUserId != user.id) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Not authorized"))
                    return@delete
                }

                val updated = groupService.removeMember(groupId, targetUserId)
                if (updated == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Group not found"))
                    return@delete
                }

                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}