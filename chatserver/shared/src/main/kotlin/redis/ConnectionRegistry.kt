package redis

import io.lettuce.core.RedisClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

class ConnectionRegistry(redisUrl: String) {
    private val client = RedisClient.create(redisUrl)
    private val connection = client.connect()

    private val serverId = System.getenv("SERVER_ID") ?: UUID.randomUUID().toString()

    /**
     * Register user as online
     * TTL: 60 seconds (renewed by heartbeat)
     */
    suspend fun registerOnline(userId: String): Long? = withContext(Dispatchers.IO) {
        connection.async().setex("online:$userId", 60, serverId).get()
        connection.async().sadd("server:$serverId:users", userId).get()
    }

    suspend fun registerOffline(userId: String): Long? = withContext(Dispatchers.IO) {
        connection.async().del("online:$userId").get()
        connection.async().srem("server:$serverId:users", userId).get()
    }

    /**
     * Check if user is online on ANY server
     */
    suspend fun isUserOnline(userId: String) : Boolean = withContext(Dispatchers.IO) {
        return@withContext connection.async().exists("online:$userId").get() > 0
    }

    suspend fun getUserServer(userId: String): String? = withContext(Dispatchers.IO) {
        return@withContext connection.async().get("online:$userId").get()
    }

    /**
     * Heartbeat - refresh TTL for all users on this server
     * Call every 30 seconds
     */
    suspend fun heartbeat() = withContext(Dispatchers.IO) {
        val users = connection.async().smembers("server:$serverId:users").get()
        users.forEach { userId ->
            connection.async().expire("online:$userId", 60).get()
        }
    }
}