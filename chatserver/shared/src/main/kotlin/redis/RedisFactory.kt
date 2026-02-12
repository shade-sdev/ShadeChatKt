package redis

import dto.RedisPoolStats
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import kotlinx.serialization.Serializable
import org.apache.commons.pool2.BasePooledObjectFactory
import org.apache.commons.pool2.PooledObject
import org.apache.commons.pool2.impl.DefaultPooledObject
import org.apache.commons.pool2.impl.GenericObjectPool
import org.apache.commons.pool2.impl.GenericObjectPoolConfig
import java.time.Duration
import util.log
import java.util.concurrent.TimeUnit

/**
 * EXACT SAME PATTERN as your DatabaseFactory for PostgreSQL
 * Singleton - one source of truth for Redis connections
 */
object RedisFactory {

    // Singleton instances - just like HikariDataSource in DatabaseFactory
    private var redisClient: RedisClient? = null
    private var connectionPool: GenericObjectPool<StatefulRedisConnection<String, String>>? = null
    private var pubSubPool: GenericObjectPool<StatefulRedisPubSubConnection<String, String>>? = null

    /**
     * Initialize Redis connection pool - call once at startup
     * EXACT same pattern as DatabaseFactory.init()
     */
    fun init(): RedisPool {
        if (connectionPool == null) {
            log().info { " Initializing Redis connection pool..." }

            // Read environment variables - just like your hikari() method
            val host = System.getenv("REDIS_HOST") ?: "localhost"
            val port = (System.getenv("REDIS_PORT") ?: "6379").toInt()
            val password = System.getenv("REDIS_PASSWORD") ?: ""
            val database = (System.getenv("REDIS_DATABASE") ?: "0").toInt()
            val ssl = (System.getenv("REDIS_SSL") ?: "false").toBoolean()

            // Pool settings - SAME ENV VAR NAMES as your PostgreSQL!
            val maxPool = (System.getenv("REDIS_MAX_POOL_SIZE") ?: "10").toInt()  // matches DB_MAX_POOL_SIZE
            val minIdle = (System.getenv("REDIS_MIN_IDLE") ?: "2").toInt()        // matches DB_MIN_IDLE
            val maxIdle = (System.getenv("REDIS_MAX_IDLE") ?: "5").toInt()
            val connTimeout = (System.getenv("REDIS_CONNECTION_TIMEOUT_MS") ?: "30000").toLong()

            // Build Redis URI - just like your JDBC URL
            val uriBuilder = RedisURI.Builder
                .redis(host, port)
                .withDatabase(database)

            if (password.isNotBlank()) {
                uriBuilder.withPassword(password)
            }
            if (ssl) {
                uriBuilder.withSsl(true)
            }

            val redisUri = uriBuilder.build()
            redisClient = RedisClient.create(redisUri)

            // 🔥 FIX 1: Use modern pool config with Duration
            val poolConfig = GenericObjectPoolConfig<StatefulRedisConnection<String, String>>().apply {
                this.maxTotal = maxPool
                this.maxIdle = maxIdle
                this.minIdle = minIdle
                // Use Duration instead of deprecated maxWaitMillis
                this.setMaxWait(Duration.ofMillis(connTimeout))

                // Connection testing - Hikari does this too
                this.testOnBorrow = true
                this.testOnReturn = true
                this.testWhileIdle = true
                this.timeBetweenEvictionRuns = Duration.ofMillis(30000)
                this.minEvictableIdleDuration = Duration.ofMillis(60000)
                this.blockWhenExhausted = true
            }

            // Create regular connection pool
            connectionPool = GenericObjectPool(
                RedisConnectionFactory(redisClient!!),
                poolConfig
            )

            // 🔥 FIX 2: Create a SEPARATE config for PubSub - don't modify and reuse!
            val pubSubConfig = GenericObjectPoolConfig<StatefulRedisPubSubConnection<String, String>>().apply {
                this.maxTotal = maxPool / 2
                this.maxIdle = maxIdle / 2
                this.minIdle = minIdle / 2
                this.setMaxWait(Duration.ofMillis(connTimeout))
                this.testOnBorrow = true
                this.testOnReturn = true
                this.testWhileIdle = true
                this.timeBetweenEvictionRuns = Duration.ofMillis(30000)
                this.minEvictableIdleDuration = Duration.ofMillis(60000)
                this.blockWhenExhausted = true
            }

            // Create PubSub connection pool (separate for subscriptions)
            pubSubPool = GenericObjectPool(
                RedisPubSubConnectionFactory(redisClient!!),
                pubSubConfig
            )

            // Pre-warm the pool - just like Hikari's minimumIdle
            repeat(minIdle) {
                try {
                    connectionPool!!.addObject()
                    pubSubPool!!.addObject()
                } catch (e: Exception) {
                    log().error(e) { "Failed to pre-warm Redis pool" }
                }
            }

            log().info { "✅ Redis pool initialized: max=$maxPool, minIdle=$minIdle, active=${connectionPool!!.numActive}, idle=${connectionPool!!.numIdle}" }
        }

        return RedisPool(connectionPool!!, pubSubPool!!)
    }

    /**
     * Connection factory for regular Redis connections
     */
    private class RedisConnectionFactory(
        private val client: RedisClient
    ) : BasePooledObjectFactory<StatefulRedisConnection<String, String>>() {

        override fun create(): StatefulRedisConnection<String, String> {
            return client.connect()
        }

        override fun wrap(conn: StatefulRedisConnection<String, String>): PooledObject<StatefulRedisConnection<String, String>> {
            return DefaultPooledObject(conn)
        }

        // 🔥 FIX 3: Use 'obj' parameter correctly
        override fun validateObject(obj: PooledObject<StatefulRedisConnection<String, String>>): Boolean {
            return try {
                obj.`object`.sync().ping() == "PONG"  // Note: `object` is escaped with backticks
            } catch (e: Exception) {
                false
            }
        }

        override fun destroyObject(obj: PooledObject<StatefulRedisConnection<String, String>>) {
            obj.`object`.close()
        }
    }

    /**
     * Connection factory for PubSub connections
     */
    private class RedisPubSubConnectionFactory(
        private val client: RedisClient
    ) : BasePooledObjectFactory<StatefulRedisPubSubConnection<String, String>>() {

        override fun create(): StatefulRedisPubSubConnection<String, String> {
            return client.connectPubSub()
        }

        override fun wrap(conn: StatefulRedisPubSubConnection<String, String>): PooledObject<StatefulRedisPubSubConnection<String, String>> {
            return DefaultPooledObject(conn)
        }

        // 🔥 FIX 4: PubSub connections can't ping the same way, check if open instead
        override fun validateObject(obj: PooledObject<StatefulRedisPubSubConnection<String, String>>): Boolean {
            return try {
                obj.`object`.isOpen
            } catch (e: Exception) {
                false
            }
        }

        override fun destroyObject(obj: PooledObject<StatefulRedisPubSubConnection<String, String>>) {
            obj.`object`.close()
        }
    }

    /**
     * Clean shutdown - call during application shutdown
     */
    fun close() {
        log().info { "Shutting down Redis pool..." }
        connectionPool?.close()
        pubSubPool?.close()
        redisClient?.shutdown()
        redisClient?.shutdownAsync()?.get(5, TimeUnit.SECONDS)
        connectionPool = null
        pubSubPool = null
        redisClient = null
        log().info { " Redis pool closed" }
    }
}

/**
 * Redis pool wrapper - gives you connections from the pool
 */
data class RedisPool(
    private val connectionPool: GenericObjectPool<StatefulRedisConnection<String, String>>,
    private val pubSubPool: GenericObjectPool<StatefulRedisPubSubConnection<String, String>>
) {

    /**
     * Execute with a regular Redis connection (auto-returned to pool)
     * USAGE: redisPool.withConnection { conn -> conn.async().get("key") }
     */
    fun <T> withConnection(block: (StatefulRedisConnection<String, String>) -> T): T {
        val conn = connectionPool.borrowObject()
        try {
            return block(conn)
        } finally {
            connectionPool.returnObject(conn)
        }
    }

    /**
     * Execute with a PubSub connection (auto-returned to pool)
     * Note: Only use this for SHORT operations!
     */
    fun <T> withPubSubConnection(block: (StatefulRedisPubSubConnection<String, String>) -> T): T {
        val conn = pubSubPool.borrowObject()
        try {
            return block(conn)
        } finally {
            pubSubPool.returnObject(conn)
        }
    }

    /**
     * Borrow a PubSub connection for long-lived subscription
     * Caller MUST return it with returnPubSubConnection() when done!
     */
    fun borrowPubSubConnection(): StatefulRedisPubSubConnection<String, String> {
        return pubSubPool.borrowObject()
    }

    /**
     * Return a borrowed PubSub connection
     */
    fun returnPubSubConnection(conn: StatefulRedisPubSubConnection<String, String>) {
        pubSubPool.returnObject(conn)
    }

    /**
     * Get pool statistics - for monitoring
     */
    fun stats(): RedisPoolStats = RedisPoolStats(
        active = connectionPool.numActive,
        idle = connectionPool.numIdle,
        total = connectionPool.numActive + connectionPool.numIdle,
        maxTotal = connectionPool.maxTotal,
        created = connectionPool.createdCount,
        destroyed = connectionPool.destroyedCount,
        waiters = connectionPool.numWaiters
    )
}