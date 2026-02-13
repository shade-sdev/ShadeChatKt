package routes

import auth.UserPrincipal
import dto.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import model.CallType
import service.CallService
import util.log

fun Route.callRoutes(callService: CallService) {
    route("/calls") {
        authenticate {
            // Initiate a new call
            post {
                val user = call.principal<UserPrincipal>()?.user
                    ?: return@post call.respond(HttpStatusCode.Unauthorized)

                val request = call.receive<InitiateCallRequest>()

                try {
                    val callType = when (request.callType.lowercase()) {
                        "dm" -> CallType.DM
                        "group" -> CallType.GROUP
                        else -> {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse("Invalid call type. Must be 'dm' or 'group'")
                            )
                            return@post
                        }
                    }

                    val callResponse = callService.initiateCall(
                        userId = user.id,
                        conversationId = request.conversationId,
                        callType = callType
                    )

                    call.respond(HttpStatusCode.Created, callResponse)
                } catch (e: IllegalArgumentException) {
                    log().warn { "Bad request for call initiation: ${e.message}" }
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Invalid request"))
                } catch (e: IllegalStateException) {
                    log().warn { "Call already in progress: ${e.message}" }
                    call.respond(HttpStatusCode.Conflict, ErrorResponse(e.message ?: "Call already in progress"))
                } catch (e: Exception) {
                    log().error(e) { "Error initiating call" }
                    call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to initiate call"))
                }
            }

            // Join an existing call (get token)
            post("/{id}/join") {
                val user = call.principal<UserPrincipal>()?.user
                    ?: return@post call.respond(HttpStatusCode.Unauthorized)

                val callId = call.parameters["id"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing call ID"))
                    return@post
                }

                try {
                    val tokenResponse = callService.joinCall(user.id, callId)
                    call.respond(tokenResponse)
                } catch (e: IllegalArgumentException) {
                    log().warn { "Cannot join call: ${e.message}" }
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Cannot join call"))
                } catch (e: IllegalStateException) {
                    log().warn { "Call not active: ${e.message}" }
                    call.respond(HttpStatusCode.Gone, ErrorResponse(e.message ?: "Call has ended"))
                } catch (e: Exception) {
                    log().error(e) { "Error joining call" }
                    call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to join call"))
                }
            }

            // End a call
            post("/{id}/end") {
                val user = call.principal<UserPrincipal>()?.user
                    ?: return@post call.respond(HttpStatusCode.Unauthorized)

                val callId = call.parameters["id"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing call ID"))
                    return@post
                }

                try {
                    callService.endCall(user.id, callId)
                    call.respond(HttpStatusCode.NoContent)
                } catch (e: IllegalArgumentException) {
                    log().warn { "Cannot end call: ${e.message}" }
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Cannot end call"))
                } catch (e: Exception) {
                    log().error(e) { "Error ending call" }
                    call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to end call"))
                }
            }

            // Leave a call
            post("/{id}/leave") {
                val user = call.principal<UserPrincipal>()?.user
                    ?: return@post call.respond(HttpStatusCode.Unauthorized)

                val callId = call.parameters["id"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing call ID"))
                    return@post
                }

                try {
                    callService.leaveCall(user.id, callId)
                    call.respond(HttpStatusCode.NoContent)
                } catch (e: IllegalArgumentException) {
                    log().warn { "Cannot leave call: ${e.message}" }
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Cannot leave call"))
                } catch (e: Exception) {
                    log().error(e) { "Error leaving call" }
                    call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Failed to leave call"))
                }
            }

            // Get call details
            get("/{id}") {
                val user = call.principal<UserPrincipal>()?.user
                    ?: return@get call.respond(HttpStatusCode.Unauthorized)

                val callId = call.parameters["id"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing call ID"))
                    return@get
                }

                val callResponse = callService.getCall(callId)
                if (callResponse == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Call not found"))
                    return@get
                }

                call.respond(callResponse)
            }

            // Get active call for a conversation
            get("/active/{type}/{conversationId}") {
                val user = call.principal<UserPrincipal>()?.user
                    ?: return@get call.respond(HttpStatusCode.Unauthorized)

                val typeParam = call.parameters["type"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing call type"))
                    return@get
                }

                val conversationId = call.parameters["conversationId"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing conversation ID"))
                    return@get
                }

                val callType = when (typeParam.lowercase()) {
                    "dm" -> CallType.DM
                    "group" -> CallType.GROUP
                    else -> {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid call type"))
                        return@get
                    }
                }

                val activeCall = callService.getActiveCall(conversationId, callType)
                if (activeCall == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("No active call"))
                    return@get
                }

                call.respond(activeCall)
            }
        }
    }
}