package websocket

import dto.WSMessage
import io.ktor.websocket.*
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.serialization.json.Json
import redis.LocalMessageDelivery
import repository.GroupRepository
import util.log
import java.util.concurrent.ConcurrentHashMap

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
        log().info { "User $userId connected locally. Total: ${connections.size}" }
    }

    fun removeConnection(userId: String) {
        connections.remove(userId)
        log().info { "User $userId disconnected locally. Total: ${connections.size}" }
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
                log().info { "📨 Delivered to user $userId locally" }
            } catch (e: Exception) {
                log().error(e) { "Error during deliver to user $userId" }
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
                    log().error(e) { "Error broadcasting: ${e.message}" }
                }
            }
        }

        log().info { "Broadcast to ${connections.size} local users" }
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
                    log().error(e) { "Error delivering to group member $userId: ${e.message}" }
                }
            }
        }
    }
}