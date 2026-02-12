package websocket

import dto.WSMessage
import io.ktor.websocket.*
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import redis.LocalMessageDelivery
import repository.GroupRepository

/**
 * Manages WebSocket connections on THIS server
 * Implements LocalMessageDelivery for Redis integration
 */
class WebSocketConnectionManager : LocalMessageDelivery {
    private val connections = ConcurrentHashMap<String, DefaultWebSocketSession>()
    private var groupRepository: GroupRepository? = null

    fun setGroupRepository(repo: GroupRepository) {
        groupRepository = repo
    }

    fun addConnection(userId: String, session: DefaultWebSocketSession) {
        connections[userId] = session
        println("👤 User $userId connected locally. Total: ${connections.size}")
    }

    fun removeConnection(userId: String) {
        connections.remove(userId)
        println("👋 User $userId disconnected locally. Total: ${connections.size}")
    }

    fun getConnection(userId: String): DefaultWebSocketSession? {
        return connections[userId]
    }

    fun isUserConnected(userId: String): Boolean {
        return connections.containsKey(userId)
    }

    fun getConnectedUserIds(): List<String> {
        return connections.keys.toList()
    }

    @OptIn(DelicateCoroutinesApi::class)
    override suspend fun deliverToUser(userId: String, message: WSMessage) {
        val session = connections[userId]
        if (session != null && !session.outgoing.isClosedForSend) {
            try {
                val json = Json.encodeToString(message)
                session.send(Frame.Text(json))
                println("📨 Delivered to user $userId locally")
            } catch (e: Exception) {
                println("❌ Error delivering to user $userId: ${e.message}")
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    override suspend fun deliverToAll(message: WSMessage) {
        val json = Json.encodeToString(message)
        connections.values.forEach { session ->
            if (!session.outgoing.isClosedForSend) {
                try {
                    session.send(Frame.Text(json))
                } catch (e: Exception) {
                    println("❌ Error broadcasting: ${e.message}")
                }
            }
        }
        println("📡 Broadcast to ${connections.size} local users")
    }

    @OptIn(DelicateCoroutinesApi::class)
    override suspend fun deliverToGroup(groupId: String, message: WSMessage) {
        val group = groupRepository?.findById(groupId) ?: return
        val recipients = (group.memberIds + group.adminId).distinct()

        val json = Json.encodeToString(message)
        recipients.forEach { userId ->
            val session = connections[userId]
            if (session != null && !session.outgoing.isClosedForSend) {
                try {
                    session.send(Frame.Text(json))
                } catch (e: Exception) {
                    println("❌ Error delivering to group member $userId: ${e.message}")
                }
            }
        }
    }
}