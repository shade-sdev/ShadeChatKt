package routes

import auth.JWTConfig
import model.UserStatus
import service.UserService
import websocket.WebSocketConnectionManager
import dto.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import repository.DMRepository
import repository.GroupRepository

fun Route.websocketRoute(
    wsManager: WebSocketConnectionManager,
    userService: UserService,
    dmRepository: DMRepository,
    groupRepository: GroupRepository
) {
    webSocket("/ws") {
        var userId: String? = null

        try {
            // Authenticate via query parameter token
            val token = call.request.queryParameters["token"]
            userId = token?.let { JWTConfig.verifyToken(it) }

            if (userId == null) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
                return@webSocket
            }

            // Register connection and update user status
            wsManager.addConnection(userId, this)
            userService.updateStatus(userId, UserStatus.ONLINE)

            println("User $userId connected via WebSocket")

            // Handle incoming messages
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    val text = frame.readText()
                    handleWebSocketMessage(text, userId, wsManager, dmRepository, groupRepository)
                }
            }
        } catch (e: Exception) {
            println("WebSocket error for user $userId: ${e.message}")
            e.printStackTrace()
        } finally {
            // Clean up on disconnect
            if (userId != null) {
                wsManager.removeConnection(userId)
                userService.updateStatus(userId, UserStatus.OFFLINE)
                println("User $userId disconnected from WebSocket")
            }
        }
    }
}

private suspend fun handleWebSocketMessage(
    text: String,
    userId: String,
    wsManager: WebSocketConnectionManager,
    dmRepository: DMRepository,
    groupRepository: GroupRepository
) {
    try {
        println("=== WebSocket message from user: $userId ===")
        println("Raw text: $text")

        // Parse as JSON object to check structure
        val json = Json.parseToJsonElement(text).jsonObject
        val type = json["type"]?.jsonPrimitive?.content

        if (type == null) {
            println("No type field in message")
            return
        }

        println("Message type: $type")

        when (type) {
            "ping" -> {
                // Respond to ping with pong
                println("Received ping from user $userId")
                val pongMessage = mapOf("type" to "pong")
                val jsonResponse = Json.encodeToString(pongMessage)
                wsManager.getConnection(userId)?.send(Frame.Text(jsonResponse))
            }

            "typing_indicator" -> {
                // Parse typing indicator
                val dataElement = json["data"] ?: return

                try {
                    val dataString = if (dataElement is JsonPrimitive) {
                        dataElement.content
                    } else {
                        dataElement.toString()
                    }

                    val indicator = Json.decodeFromString<TypingIndicator>(dataString)
                    println("Received typing indicator from user $userId: $indicator")

                    // Send to appropriate recipients based on conversation type
                    when (indicator.conversationType) {
                        "dm" -> {
                            // For DM: send to the other participant only
                            val dm = dmRepository.findById(indicator.conversationId)
                            if (dm != null) {
                                val recipientId = if (dm.participant1Id == userId) dm.participant2Id else dm.participant1Id
                                println("Sending DM typing indicator from $userId to $recipientId")
                                wsManager.sendToUser(recipientId, indicator)
                            } else {
                                println("DM not found: ${indicator.conversationId}")
                            }
                        }
                        "group" -> {
                            // For group: send to all group members except the sender
                            val group = groupRepository.findById(indicator.conversationId)
                            if (group != null) {
                                val recipients = (group.memberIds + group.adminId).distinct()
                                    .filter { it != userId } // Exclude sender

                                println("Sending group typing indicator from $userId to ${recipients.size} recipients")
                                recipients.forEach { recipientId ->
                                    wsManager.sendToUser(recipientId, indicator)
                                }
                            } else {
                                println("Group not found: ${indicator.conversationId}")
                            }
                        }
                        else -> {
                            println("Unknown conversation type: ${indicator.conversationType}")
                        }
                    }
                } catch (e: Exception) {
                    println("Error parsing typing_indicator: ${e.message}")
                    e.printStackTrace()
                }
            }

            else -> {
                println("Unknown WebSocket message type: $type")
            }
        }

        println("=== End of message processing ===\n")
    } catch (e: Exception) {
        println("=== ERROR handling WebSocket message ===")
        println("Error: ${e.message}")
        e.printStackTrace()
        println("=== End error ===")
    }
}