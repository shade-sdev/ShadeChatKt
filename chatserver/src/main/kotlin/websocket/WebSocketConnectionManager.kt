package websocket

import dto.*
import io.ktor.websocket.*
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

class WebSocketConnectionManager {
    private val connections = ConcurrentHashMap<String, DefaultWebSocketSession>()

    fun addConnection(userId: String, session: DefaultWebSocketSession) {
        connections[userId] = session
        println("User $userId connected. Total connections: ${connections.size}")
    }

    fun removeConnection(userId: String) {
        connections.remove(userId)
        println("User $userId disconnected. Total connections: ${connections.size}")
    }

    fun getConnection(userId: String): DefaultWebSocketSession? {
        return connections[userId]
    }

    fun isUserConnected(userId: String): Boolean {
        return connections.containsKey(userId)
    }

    @OptIn(DelicateCoroutinesApi::class)
    suspend fun sendToUser(userId: String, message: Any) {
        val session = connections[userId]
        if (session != null && !session.outgoing.isClosedForSend) {
            try {
                val wsMessage = createWebSocketMessage(message)
                val json = Json.encodeToString(wsMessage)
                println("Sending to user $userId: ${wsMessage.type}")
                session.send(Frame.Text(json))
            } catch (e: Exception) {
                println("Error sending message to user $userId: ${e.message}")
                e.printStackTrace()
            }
        } else {
            println("Cannot send to user $userId - not connected or session closed")
        }
    }

    suspend fun sendToUsers(userIds: List<String>, message: Any) {
        userIds.forEach { userId ->
            sendToUser(userId, message)
        }
    }

    suspend fun broadcast(message: Any) {
        try {
            val wsMessage = createWebSocketMessage(message)
            val json = Json.encodeToString(wsMessage)
            println("Broadcasting: ${wsMessage.type} to ${connections.size} users")

            connections.values.forEach { session ->
                if (!session.outgoing.isClosedForSend) {
                    try {
                        session.send(Frame.Text(json))
                    } catch (e: Exception) {
                        println("Error broadcasting to a session: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            println("Error creating WebSocket message for broadcast: ${e.message}")
            e.printStackTrace()
        }
    }

    fun getConnectedUserIds(): List<String> {
        return connections.keys.toList()
    }

    private fun createWebSocketMessage(message: Any): WSMessage {
        return when (message) {
            is TypingIndicator -> {
                val jsonData = Json.encodeToString(message)
                WSMessage("typing_indicator", jsonData)
            }
            is NewMessageNotification -> {
                val jsonData = Json.encodeToString(message)
                WSMessage("new_message", jsonData)
            }
            is UserStatusUpdate -> {
                val jsonData = Json.encodeToString(message)
                WSMessage("user_status", jsonData)
            }
            is GroupInvitation -> {
                val jsonData = Json.encodeToString(message)
                WSMessage("group_invitation", jsonData)
            }
            is GroupUpdated -> {
                val jsonData = Json.encodeToString(message)
                WSMessage("group_updated", jsonData)
            }
            else -> {
                println("Unknown message type: ${message::class.simpleName}")
                WSMessage("unknown", "\"${message.toString()}\"")
            }
        }
    }
}