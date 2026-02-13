package redis

import dto.*
import io.lettuce.core.pubsub.RedisPubSubAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import util.log

/**
 * Redis Pub/Sub using shared connection pool
 * Special handling because PubSub connections are long-lived
 */
class RedisMessageBus(
    private val redisPool: RedisPool,
    private val localDelivery: LocalMessageDelivery
) {
    private val json = Json { ignoreUnknownKeys = true }

    // PubSub connections are LONG-LIVED - borrow once, never return until shutdown
    private val pubSubConnection = redisPool.borrowPubSubConnection()
    private val subConnection = redisPool.borrowPubSubConnection()

    init {
        subConnection.addListener(object : RedisPubSubAdapter<String, String>() {
            override fun message(channel: String, message: String) {
                handleIncomingMessage(channel, message)
            }

            override fun subscribed(channel: String, count: Long) {
                log().debug { " Subscribed to Redis channel: $channel" }
            }

            override fun unsubscribed(channel: String, count: Long) {
                log().debug { " Unsubscribed from Redis channel: $channel" }
            }
        })

        subConnection.async().subscribe("broadcast")
        log().info { " Subscribed to Redis broadcast channel" }
    }

    private fun handleIncomingMessage(channel: String, message: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when {
                    channel == "broadcast" -> {
                        val wsMessage = json.decodeFromString<WSMessage>(message)
                        localDelivery.deliverToAll(wsMessage)
                    }

                    channel.startsWith("user:") -> {
                        val userId = channel.removePrefix("user:")
                        val wsMessage = json.decodeFromString<WSMessage>(message)
                        localDelivery.deliverToUser(userId, wsMessage)
                    }

                    channel.startsWith("group:") -> {
                        val groupId = channel.removePrefix("group:")
                        val wsMessage = json.decodeFromString<WSMessage>(message)
                        localDelivery.deliverToGroup(groupId, wsMessage)
                    }
                }
            } catch (e: Exception) {
                log().error(e) { " Error handling Redis message: ${e.message}" }
            }
        }
    }

    suspend fun publishToUser(userId: String, message: Any) {
        val wsMessage = createWebSocketMessage(message)
        val jsonStr = json.encodeToString(wsMessage)
        pubSubConnection.async().publish("user:$userId", jsonStr)
        log().debug { " Published to Redis - user:$userId" }
    }

    suspend fun publishToGroup(groupId: String, message: Any) {
        val wsMessage = createWebSocketMessage(message)
        val jsonStr = json.encodeToString(wsMessage)
        pubSubConnection.async().publish("group:$groupId", jsonStr)
        log().debug { " Published to Redis - group:$groupId" }
    }

    suspend fun publishBroadcast(message: Any) {
        val wsMessage = createWebSocketMessage(message)
        val jsonStr = json.encodeToString(wsMessage)
        pubSubConnection.async().publish("broadcast", jsonStr)
        log().debug { " Published to Redis - broadcast" }
    }

    fun subscribeToUser(userId: String) {
        subConnection.async().subscribe("user:$userId")
        log().info { " Subscribed to user:$userId" }
    }

    fun unsubscribeFromUser(userId: String) {
        subConnection.async().unsubscribe("user:$userId")
        log().info { " Unsubscribed from user:$userId" }
    }

    fun subscribeToGroup(groupId: String) {
        subConnection.async().subscribe("group:$groupId")
        log().info { " Subscribed to group:$groupId" }
    }

    fun unsubscribeFromGroup(groupId: String) {
        subConnection.async().unsubscribe("group:$groupId")
        log().info { " Unsubscribed from group:$groupId" }
    }

    private fun createWebSocketMessage(message: Any): WSMessage {
        return when (message) {
            is TypingIndicator -> WSMessage("typing_indicator", json.encodeToString(message))
            is NewMessageNotification -> WSMessage("new_message", json.encodeToString(message))
            is UserStatusUpdate -> WSMessage("user_status", json.encodeToString(message))
            is GroupInvitation -> WSMessage("group_invitation", json.encodeToString(message))
            is GroupUpdated -> WSMessage("group_updated", json.encodeToString(message))
            is CallInvitation -> WSMessage("call_invitation", json.encodeToString(message))
            is CallStatusUpdate -> WSMessage("call_status", json.encodeToString(message))
            else -> WSMessage("unknown", "\"${message.toString()}\"")
        }
    }

    fun close() {
        log().info { "Closing RedisMessageBus connections..." }
        try {
            subConnection.async().unsubscribe("broadcast")

            redisPool.returnPubSubConnection(pubSubConnection)
            redisPool.returnPubSubConnection(subConnection)
            log().info { " RedisMessageBus closed" }
        } catch (e: Exception) {
            log().error(e) { "Error closing RedisMessageBus" }
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