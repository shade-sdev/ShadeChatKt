package redis

import io.lettuce.core.RedisClient
import jobs.AddMemberJob
import jobs.CreateGroupJob
import jobs.Job
import jobs.RemoveMemberJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Redis job queue for background processing
 * API servers enqueue, workers dequeue
 */
class JobQueue(redisUrl: String) {
    private val client = RedisClient.create(redisUrl)
    private val connection = client.connect()

    suspend fun enqueue(job: Job) = withContext(Dispatchers.IO) {
        val json = when (job) {
            is CreateGroupJob -> Json.encodeToString(job)
            is AddMemberJob -> Json.encodeToString(job)
            is RemoveMemberJob -> Json.encodeToString(job)
        }

        connection.async().lpush("jobs:pending", json).get()
        println("📤 Enqueued: ${job::class.simpleName} ${job.id}")
    }

    suspend fun dequeue(): Job? = withContext(Dispatchers.IO) {
        val result = connection.async().brpop(5, "jobs:pending").get()

        if (result != null && result.hasValue()) {
            val jobJson = result.value

            return@withContext try {
                when {
                    jobJson.contains("\"name\"") -> Json.decodeFromString<CreateGroupJob>(jobJson)
                    jobJson.contains("\"groupId\"") && jobJson.contains("\"userId\"") -> {
                        if (jobJson.contains("AddMember")) {
                            Json.decodeFromString<AddMemberJob>(jobJson)
                        } else {
                            Json.decodeFromString<RemoveMemberJob>(jobJson)
                        }
                    }

                    else -> null
                }
            } catch (e: Exception) {
                println("❌ Failed to deserialize job: ${e.message}")
                null
            }
        }

        return@withContext null
    }
}