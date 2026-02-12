package redis

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import util.log
import java.util.*

/**
 * Connection registry using shared Redis connection pool
 * Tracks user presence across all API servers
 */
class ConnectionRegistry(
    private val redisPool: RedisPool
) {
    private val serverId = System.getenv("SERVER_ID") ?: UUID.randomUUID().toString()

    suspend fun registerOnline(userId: String) = withContext(Dispatchers.IO) {
        redisPool.withConnection { conn ->
            conn.async().setex("online:$userId", 60, serverId).get()
            conn.async().sadd("server:$serverId:users", userId).get()
            log().info { " User $userId online on server $serverId" }
        }
    }

    suspend fun registerOffline(userId: String) = withContext(Dispatchers.IO) {
        redisPool.withConnection { conn ->
            conn.async().del("online:$userId").get()
            conn.async().srem("server:$serverId:users", userId).get()
            log().info { " User $userId offline from server $serverId" }
        }
    }

    suspend fun isUserOnline(userId: String): Boolean = withContext(Dispatchers.IO) {
        redisPool.withConnection { conn ->
            conn.async().exists("online:$userId").get() > 0
        }
    }

    suspend fun getUserServer(userId: String): String? = withContext(Dispatchers.IO) {
        redisPool.withConnection { conn ->
            conn.async().get("online:$userId").get()
        }
    }

    suspend fun heartbeat() = withContext(Dispatchers.IO) {
        redisPool.withConnection { conn ->
            val users = conn.async().smembers("server:$serverId:users").get()
            users.forEach { userId ->
                conn.async().expire("online:$userId", 60).get()
            }
            log().debug { " Heartbeat sent for ${users.size} users on server $serverId" }
        }
    }

    suspend fun getConnectedUsersCount(): Long = withContext(Dispatchers.IO) {
        redisPool.withConnection { conn ->
            conn.async().scard("server:$serverId:users").get()
        }
    }

    suspend fun getTotalOnlineUsers(): Long = withContext(Dispatchers.IO) {
        redisPool.withConnection { conn ->
            val keys = conn.async().keys("online:*").get()
            keys.size.toLong()
        }
    }

    suspend fun removeServer() = withContext(Dispatchers.IO) {
        redisPool.withConnection { conn ->
            val users = conn.async().smembers("server:$serverId:users").get()
            users.forEach { userId ->
                conn.async().del("online:$userId").get()
            }
            conn.async().del("server:$serverId:users").get()
            log().info { "🗑️ Removed server $serverId with ${users.size} users" }
        }
    }

    suspend fun getAllServers(): List<String> = withContext(Dispatchers.IO) {
        redisPool.withConnection { conn ->
            val keys = conn.async().keys("server:*:users").get()
            keys.map { it.removePrefix("server:").removeSuffix(":users") }
        }
    }

}