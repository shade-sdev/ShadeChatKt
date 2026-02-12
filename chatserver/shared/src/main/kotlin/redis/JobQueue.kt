package redis

import jobs.AddMemberJob
import jobs.CreateGroupJob
import jobs.Job
import jobs.RemoveMemberJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import util.log

/**
 * Job queue using shared Redis connection pool
 * Just like your repositories use HikariCP!
 */
class JobQueue(
    private val redisPool: RedisPool  // Injected from RedisFactory
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun enqueue(job: Job) = withContext(Dispatchers.IO) {
        val jsonJob = json.encodeToString(job)

        redisPool.withConnection { conn ->
            conn.async().lpush("jobs:pending", jsonJob).get()
            log().info { " Enqueued: ${job::class.simpleName} ${job.id}" }
        }
    }

    suspend fun dequeue(): Job? = withContext(Dispatchers.IO) {
        redisPool.withConnection { conn ->
            val jobJson = conn.async().rpoplpush("jobs:pending", "jobs:processing").get()

            if (jobJson != null) {
                conn.async().expire("jobs:processing", 300).get() // 5 min TTL

                try {
                    val job = deserializeJob(jobJson)
                    log().info { "📬 Dequeued: ${job::class.simpleName} ${job.id}" }
                    return@withConnection job
                } catch (e: Exception) {
                    log().error(e) { "Failed to deserialize job" }
                    conn.async().lrem("jobs:processing", 1, jobJson).get()
                    moveToDeadLetter(jobJson, "Deserialization failed: ${e.message}")
                    null
                }
            } else {
                null
            }
        }
    }

    suspend fun ack(job: Job) = withContext(Dispatchers.IO) {
        redisPool.withConnection { conn ->
            val jsonJob = json.encodeToString(job)
            conn.async().lrem("jobs:processing", 1, jsonJob).get()
            log().info { " Job acknowledged: ${job.id}" }
        }
    }

    suspend fun requeue(job: Job, delaySeconds: Long = 5) = withContext(Dispatchers.IO) {
        redisPool.withConnection { conn ->
            val jsonJob = json.encodeToString(job)
            conn.async().lrem("jobs:processing", 1, jsonJob).get()
            conn.async().zadd(
                "jobs:delayed",
                System.currentTimeMillis() + (delaySeconds * 1000),
                jsonJob
            ).get()
            log().warn { " Job requeued (${delaySeconds}s): ${job.id}" }
        }
    }

    private fun moveToDeadLetter(jobJson: String, reason: String) {
        redisPool.withConnection { conn ->
            val deadLetterEntry = mapOf(
                "job" to jobJson,
                "failedAt" to System.currentTimeMillis().toString(),
                "reason" to reason
            )
            conn.async().lpush("jobs:dead", json.encodeToString(deadLetterEntry)).get()
            log().error { " Job moved to dead letter: $reason" }
        }
    }

    private fun deserializeJob(jobJson: String): Job {
        return when {
            jobJson.contains("\"name\"") -> json.decodeFromString<CreateGroupJob>(jobJson)
            jobJson.contains("\"groupId\"") && jobJson.contains("\"userId\"") -> {
                if (jobJson.contains("AddMember")) {
                    json.decodeFromString<AddMemberJob>(jobJson)
                } else {
                    json.decodeFromString<RemoveMemberJob>(jobJson)
                }
            }

            else -> throw IllegalArgumentException("Unknown job type")
        }
    }

    suspend fun getQueueLength(): Long = withContext(Dispatchers.IO) {
        redisPool.withConnection { conn ->
            conn.async().llen("jobs:pending").get()
        }
    }

    suspend fun getProcessingCount(): Long = withContext(Dispatchers.IO) {
        redisPool.withConnection { conn ->
            conn.async().llen("jobs:processing").get()
        }
    }

    suspend fun getDeadLetterCount(): Long = withContext(Dispatchers.IO) {
        redisPool.withConnection { conn ->
            conn.async().llen("jobs:dead").get()
        }
    }
}