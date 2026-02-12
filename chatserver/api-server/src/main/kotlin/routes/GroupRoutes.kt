package routes

import auth.UserPrincipal
import service.GroupService
import dto.*
import validation.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import service.MessageService
import redis.JobQueue
import jobs.*
import java.util.UUID

fun Route.groupRoutes(
    groupService: GroupService,
    messageService: MessageService,
    jobQueue: JobQueue
) {
    route("/groups") {
        authenticate {
            rateLimit(RateLimitName("write")) {
                post {
                    val user = call.principal<UserPrincipal>()?.user
                        ?: return@post call.respond(HttpStatusCode.Unauthorized)
                    val request = call.receive<CreateGroupRequest>()

                    val validation = validateCreateGroupRequest(request)
                    if (validation.errors.isNotEmpty()) {
                        call.respond(HttpStatusCode.BadRequest,    ErrorResponse(
                            error = "Validation failed",
                            details = validation.errors.map { it.message }
                        ))
                        return@post
                    }

                    val job = CreateGroupJob(
                        id = UUID.randomUUID().toString(),
                        createdAt = System.currentTimeMillis(),
                        name = sanitizeInput(request.name),
                        description = request.description?.let { sanitizeInput(it) },
                        adminId = user.id,
                        memberIds = request.memberIds
                    )

                    jobQueue.enqueue(job)

                    call.respond(HttpStatusCode.Accepted,  JobAcceptedResponse(
                        jobId = job.id,
                        message = "Group creation in progress"
                    ))
                }
            }

            rateLimit(RateLimitName("write")) {
                post("/{id}/messages") {
                    val user = call.principal<UserPrincipal>()?.user
                        ?: return@post call.respond(HttpStatusCode.Unauthorized)
                    val groupId = call.parameters["id"] ?: run {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing group ID"))
                        return@post
                    }
                    val request = call.receive<SendMessageRequest>()

                    val validation = validateSendMessageRequest(request)
                    if (validation.errors.isNotEmpty()) {
                        call.respond(HttpStatusCode.BadRequest,  ErrorResponse(
                            error = "Validation failed",
                            details = validation.errors.map { it.message }
                        ))
                        return@post
                    }

                    val group = groupService.findById(groupId)
                    if (group == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Group not found"))
                        return@post
                    }

                    if (user.id !in group.memberIds) {
                        call.respond(HttpStatusCode.Forbidden, ErrorResponse("Not a member"))
                        return@post
                    }

                    val message = messageService.sendMessage(
                        user.id,
                        groupId,
                        null,
                        sanitizeInput(request.content)
                    )
                    call.respond(HttpStatusCode.Created, MessageIdResponse(id = message.id))
                }
            }

            get("/{id}/messages") {
                val user = call.principal<UserPrincipal>()?.user
                    ?: return@get call.respond(HttpStatusCode.Unauthorized)
                val groupId = call.parameters["id"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing group ID"))
                    return@get
                }

                val cursor = call.request.queryParameters["cursor"]
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 50

                val group = groupService.findById(groupId)
                if (group == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Group not found"))
                    return@get
                }

                if (user.id !in group.memberIds) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Not a member"))
                    return@get
                }

                val messages = messageService.getGroupMessages(groupId, cursor, limit)
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
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing group ID"))
                    return@get
                }

                val group = groupService.findById(groupId)
                if (group == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Group not found"))
                    return@get
                }

                if (user.id !in group.memberIds) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Not a member"))
                    return@get
                }

                call.respond(groupService.toResponse(group))
            }

            delete("/{id}") {
                val user = call.principal<UserPrincipal>()?.user
                    ?: return@delete call.respond(HttpStatusCode.Unauthorized)
                val groupId = call.parameters["id"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing group ID"))
                    return@delete
                }

                val group = groupService.findById(groupId)
                if (group == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Group not found"))
                    return@delete
                }

                if (group.adminId != user.id) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Only admin can delete group"))
                    return@delete
                }

                groupService.deleteGroup(groupId)
                call.respond(HttpStatusCode.NoContent)
            }

            rateLimit(RateLimitName("write")) {
                post("/{id}/members") {
                    val user = call.principal<UserPrincipal>()?.user
                        ?: return@post call.respond(HttpStatusCode.Unauthorized)
                    val groupId = call.parameters["id"] ?: run {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing group ID"))
                        return@post
                    }
                    val request = call.receive<AddMemberRequest>()

                    val group = groupService.findById(groupId)
                    if (group == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Group not found"))
                        return@post
                    }

                    if (group.adminId != user.id) {
                        call.respond(HttpStatusCode.Forbidden, ErrorResponse("Only admin can add members"))
                        return@post
                    }

                    val job = AddMemberJob(
                        id = UUID.randomUUID().toString(),
                        createdAt = System.currentTimeMillis(),
                        groupId = groupId,
                        userId = request.userId
                    )

                    jobQueue.enqueue(job)

                    call.respond(HttpStatusCode.Accepted, JobAcceptedResponse(
                        jobId = job.id,
                        message = "Adding member in progress"
                    ))
                }

                delete("/{id}/members/{userId}") {
                    val user = call.principal<UserPrincipal>()?.user
                        ?: return@delete call.respond(HttpStatusCode.Unauthorized)
                    val groupId = call.parameters["id"] ?: run {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing group ID"))
                        return@delete
                    }
                    val targetUserId = call.parameters["userId"] ?: run {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing user ID"))
                        return@delete
                    }

                    val group = groupService.findById(groupId)
                    if (group == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Group not found"))
                        return@delete
                    }

                    if (group.adminId != user.id && targetUserId != user.id) {
                        call.respond(HttpStatusCode.Forbidden, ErrorResponse("Not authorized"))
                        return@delete
                    }

                    val job = RemoveMemberJob(
                        id = UUID.randomUUID().toString(),
                        createdAt = System.currentTimeMillis(),
                        groupId = groupId,
                        userId = targetUserId
                    )

                    jobQueue.enqueue(job)

                    call.respond(HttpStatusCode.Accepted,  JobAcceptedResponse(
                        jobId = job.id,
                        message = "Removing member in progress"
                    ))
                }
            }
        }
    }
}