package redis

import dto.NewMessageNotification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import util.log

/**
 * Offline message queue using shared Redis connection pool
 */
class MessageQueue(
    private val redisPool: RedisPool
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun queueMessageForUser(userId: String, message: NewMessageNotification) = withContext(Dispatchers.IO) {
        redisPool.withConnection { conn ->
            val key = "pending:$userId"
            val jsonMsg = json.encodeToString(message)
            conn.async().lpush(key, jsonMsg).get()
            conn.async().expire(key, 604800).get() // 7 days
            log().info { "📥 Queued message for offline user: $userId" }
        }
    }

    suspend fun getPendingMessages(userId: String): List<NewMessageNotification> = withContext(Dispatchers.IO) {
        redisPool.withConnection { conn ->
            val key = "pending:$userId"
            val messages = conn.async().lrange(key, 0, -1).get()
            conn.async().del(key).get()

            log().info { " Retrieved ${messages.size} pending messages for: $userId" }

            return@withConnection messages.mapNotNull { jsonStr ->
                try {
                    json.decodeFromString<NewMessageNotification>(jsonStr)
                } catch (e: Exception) {
                    log().error(e) { "Failed to parse pending message" }
                    null
                }
            }
        }
    }

    suspend fun hasPendingMessages(userId: String): Boolean = withContext(Dispatchers.IO) {
        redisPool.withConnection { conn ->
            conn.async().llen("pending:$userId").get() > 0
        }
    }

    suspend fun getPendingMessageCount(userId: String): Long = withContext(Dispatchers.IO) {
        redisPool.withConnection { conn ->
            conn.async().llen("pending:$userId").get()
        }
    }

    suspend fun clearPendingMessages(userId: String) = withContext(Dispatchers.IO) {
        redisPool.withConnection { conn ->
            conn.async().del("pending:$userId").get()
            log().info { " Cleared pending messages for: $userId" }
        }
    }
}