package redis

import dto.*
import io.lettuce.core.RedisClient
import io.lettuce.core.pubsub.RedisPubSubAdapter
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Redis Pub/Sub for real-time message distribution
 * Enables communication between API servers
 */
class RedisMessageBus(
    redisUrl: String,
    private val localDelivery: LocalMessageDelivery
) {
    private val client = RedisClient.create(redisUrl)
    private val pubConnection: StatefulRedisPubSubConnection<String, String> = client.connectPubSub()
    private val subConnection: StatefulRedisPubSubConnection<String, String> = client.connectPubSub()

    init {
        subConnection.addListener(object : RedisPubSubAdapter<String, String>() {
            override fun message(channel: String, message: String) {
                handleIncomingMessage(channel, message)
            }
        })

        subConnection.async().subscribe("broadcast")
        println("🔔 Subscribed to Redis broadcast channel")
    }

    private fun handleIncomingMessage(channel: String, message: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when {
                    channel == "broadcast" -> {
                        val wsMessage = Json.decodeFromString<WSMessage>(message)
                        localDelivery.deliverToAll(wsMessage)
                    }
                    channel.startsWith("user:") -> {
                        val userId = channel.removePrefix("user:")
                        val wsMessage = Json.decodeFromString<WSMessage>(message)
                        localDelivery.deliverToUser(userId, wsMessage)
                    }
                    channel.startsWith("group:") -> {
                        val groupId = channel.removePrefix("group:")
                        val wsMessage = Json.decodeFromString<WSMessage>(message)
                        localDelivery.deliverToGroup(groupId, wsMessage)
                    }
                }
            } catch (e: Exception) {
                println("❌ Error handling Redis message: ${e.message}")
            }
        }
    }

    suspend fun publishToUser(userId: String, message: Any) {
        val wsMessage = createWebSocketMessage(message)
        val json = Json.encodeToString(wsMessage)

        pubConnection.async().publish("user:$userId", json)
        println("📤 Published to Redis - user:$userId")
    }

    suspend fun publishToGroup(groupId: String, message: Any) {
        val wsMessage = createWebSocketMessage(message)
        val json = Json.encodeToString(wsMessage)

        pubConnection.async().publish("group:$groupId", json)
        println("📤 Published to Redis - group:$groupId")
    }

    suspend fun publishBroadcast(message: Any) {
        val wsMessage = createWebSocketMessage(message)
        val json = Json.encodeToString(wsMessage)

        pubConnection.async().publish("broadcast", json)
        println("📤 Published to Redis - broadcast")
    }

    fun subscribeToUser(userId: String) {
        subConnection.async().subscribe("user:$userId")
        println("🔔 Subscribed to user:$userId")
    }

    fun unsubscribeFromUser(userId: String) {
        subConnection.async().unsubscribe("user:$userId")
        println("🔕 Unsubscribed from user:$userId")
    }

    fun subscribeToGroup(groupId: String) {
        subConnection.async().subscribe("group:$groupId")
        println("🔔 Subscribed to group:$groupId")
    }

    fun unsubscribeFromGroup(groupId: String) {
        subConnection.async().unsubscribe("group:$groupId")
        println("🔕 Unsubscribed from group:$groupId")
    }

    private fun createWebSocketMessage(message: Any): WSMessage {
        return when (message) {
            is TypingIndicator -> WSMessage("typing_indicator", Json.encodeToString(message))
            is NewMessageNotification -> WSMessage("new_message", Json.encodeToString(message))
            is UserStatusUpdate -> WSMessage("user_status", Json.encodeToString(message))
            is GroupInvitation -> WSMessage("group_invitation", Json.encodeToString(message))
            is GroupUpdated -> WSMessage("group_updated", Json.encodeToString(message))
            else -> WSMessage("unknown", "\"${message.toString()}\"")
        }
    }
}

/**
 * Interface for local WebSocket delivery
 * Implemented by WebSocketConnectionManager
 */
interface LocalMessageDelivery {
    suspend fun deliverToUser(userId: String, message: WSMessage)
    suspend fun deliverToAll(message: WSMessage)
    suspend fun deliverToGroup(groupId: String, message: WSMessage)
}