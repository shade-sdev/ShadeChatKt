package routes

import auth.JWTConfig
import dto.TypingIndicator
import dto.WSMessage
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import model.UserStatus
import redis.ConnectionRegistry
import redis.MessageQueue
import redis.RedisMessageBus
import repository.DMRepository
import repository.GroupRepository
import service.UserService
import websocket.WebSocketConnectionManager

/**
 * A plugin to echo the WebSocket sub-protocol header.
 * This satisfies the 'Sec-WebSocket-Protocol' handshake requirement.
 */
val WebSocketSubprotocolPlugin = createRouteScopedPlugin("WebSocketSubprotocolPlugin") {
    onCall { call ->
        val protocols = call.request.headers["Sec-WebSocket-Protocol"]
        if (!protocols.isNullOrBlank()) {
            val selectedProtocol = protocols.split(",")[0].trim()
            call.response.headers.append("Sec-WebSocket-Protocol", selectedProtocol)
        }
    }
}

/**
 * WebSocket route with secure token + offline queue
 */
fun Route.websocketRoute(
    wsManager: WebSocketConnectionManager,
    userService: UserService,
    dmRepository: DMRepository,
    groupRepository: GroupRepository,
    messageBus: RedisMessageBus,
    messageQueue: MessageQueue,
    connectionRegistry: ConnectionRegistry
) {

    install(WebSocketSubprotocolPlugin)

    webSocket("/ws") {
        var userId: String? = null

        try {
            val protocols = call.request.headers["Sec-WebSocket-Protocol"]
            val token = protocols?.split(",")?.firstOrNull()?.trim()

            userId = token?.let { JWTConfig.verifyToken(it) }

            if (userId == null) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
                return@webSocket
            }

            wsManager.addConnection(userId, this)
            messageBus.subscribeToUser(userId)
            connectionRegistry.registerOnline(userId)
            userService.updateStatus(userId, UserStatus.ONLINE)

            println("✅ User $userId connected via WebSocket")

            // Deliver pending messages
            val pendingMessages = messageQueue.getPendingMessages(userId)
            if (pendingMessages.isNotEmpty()) {
                println("📬 Delivering ${pendingMessages.size} pending messages to $userId")
                pendingMessages.forEach { notification ->
                    val wsMessage = WSMessage("new_message", Json.encodeToString(notification))
                    val json = Json.encodeToString(wsMessage)
                    send(Frame.Text(json))
                }
            }

            for (frame in incoming) {
                if (frame is Frame.Text) {
                    val text = frame.readText()
                    handleWebSocketMessage(text, userId, wsManager, dmRepository, groupRepository, messageBus)
                }
            }
        } catch (e: Exception) {
            println("❌ WebSocket error for user $userId: ${e.message}")
            e.printStackTrace()
        } finally {
            if (userId != null) {
                wsManager.removeConnection(userId)
                messageBus.unsubscribeFromUser(userId)
                connectionRegistry.registerOffline(userId)
                userService.updateStatus(userId, UserStatus.OFFLINE)
                println("👋 User $userId disconnected from WebSocket")
            }
        }
    }
}

private suspend fun handleWebSocketMessage(
    text: String,
    userId: String,
    wsManager: WebSocketConnectionManager,
    dmRepository: DMRepository,
    groupRepository: GroupRepository,
    messageBus: RedisMessageBus
) {
    try {
        val json = Json.parseToJsonElement(text).jsonObject
        val type = json["type"]?.jsonPrimitive?.content ?: return

        when (type) {
            "ping" -> {
                val pongMessage = WSMessage("pong", "{}")
                val jsonResponse = Json.encodeToString(pongMessage)
                wsManager.getConnection(userId)?.send(Frame.Text(jsonResponse))
            }

            "typing_indicator" -> {
                val dataElement = json["data"] ?: return
                val dataString = dataElement.toString()
                val indicator = Json.decodeFromString<TypingIndicator>(dataString)

                when (indicator.conversationType) {
                    "dm" -> {
                        val dm = dmRepository.findById(indicator.conversationId)
                        if (dm != null) {
                            val recipientId = if (dm.participant1Id == userId) dm.participant2Id else dm.participant1Id
                            messageBus.publishToUser(recipientId, indicator)
                        }
                    }

                    "group" -> {
                        val group = groupRepository.findById(indicator.conversationId)
                        if (group != null) {
                            val recipients = (group.memberIds + group.adminId).distinct().filter { it != userId }
                            recipients.forEach { recipientId ->
                                messageBus.publishToUser(recipientId, indicator)
                            }
                        }
                    }
                }
            }
        }
    } catch (e: Exception) {
        println("❌ Error handling WebSocket message: ${e.message}")
    }

}