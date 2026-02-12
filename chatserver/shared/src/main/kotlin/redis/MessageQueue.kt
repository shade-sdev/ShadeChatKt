package redis

import dto.NewMessageNotification
import io.lettuce.core.RedisClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Queue for offline message delivery
 * Messages stored here when recipient is offline
 * Delivered when user connects
 */
/**
 * Queue for offline message delivery
 * Messages stored here when recipient is offline
 * Delivered when user connects
 */
class MessageQueue(redisUrl: String) {
    private val client = RedisClient.create(redisUrl)
    private val connection = client.connect()

    /**
     * Queue message for offline user
     * TTL: 7 days
     */
    suspend fun queueMessageForUser(userId: String, message: NewMessageNotification) = withContext(Dispatchers.IO) {
        val json = Json.encodeToString(message)
        val key = "pending:$userId"

        connection.async().lpush(key, json).get()
        connection.async().expire(key, 604800).get() // 7 days

        println("📮 Queued message for offline user: $userId")
    }

    /**
     * Get all pending messages for user
     * Called when user connects
     */
    suspend fun getPendingMessages(userId: String): List<NewMessageNotification> = withContext(Dispatchers.IO) {
        val key = "pending:$userId"
        val messages = connection.async().lrange(key, 0, -1).get()

        connection.async().del(key).get()

        println("📬 Retrieved ${messages.size} pending messages for user: $userId")

        return@withContext messages.mapNotNull { json ->
            try {
                Json.decodeFromString<NewMessageNotification>(json)
            } catch (e: Exception) {
                println("⚠️ Failed to parse pending message: ${e.message}")
                null
            }
        }
    }

    suspend fun hasPendingMessages(userId: String): Boolean = withContext(Dispatchers.IO) {
        return@withContext connection.async().llen("pending:$userId").get() > 0
    }

    suspend fun getPendingMessageCount(userId: String): Long = withContext(Dispatchers.IO) {
        return@withContext connection.async().llen("pending:$userId").get()
    }
}