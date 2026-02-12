import database.DatabaseFactory

import jobs.AddMemberJob
import jobs.CreateGroupJob
import jobs.RemoveMemberJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import redis.JobQueue
import redis.RedisFactory
import redis.RedisMessageBus
import redis.RedisPool
import repository.GroupRepository
import repository.UserRepository
import service.GroupService
import util.log
import java.util.*

/**
 * Worker - processes background jobs
 * Pulls jobs from Redis queue and executes them
 * Uses shared Redis connection pool (same pattern as API servers)
 */
fun main() = runBlocking {
    val workerId = System.getenv("WORKER_ID") ?: "worker-${UUID.randomUUID()}"
    log().info { "🚀 Starting Worker: $workerId" }


    DatabaseFactory.init(runMigrations = false)
    log().info { "PostgreSQL initialized" }

    val redisPool: RedisPool = RedisFactory.init()
    log().info { "Redis pool initialized: ${redisPool.stats()}" }

    val jobQueue = JobQueue(redisPool)
    val messageBus = RedisMessageBus(redisPool, NoOpLocalDelivery())

    val userRepository = UserRepository()
    val groupRepository = GroupRepository()
    val groupService = GroupService(groupRepository, userRepository, messageBus)

    log().info { "Worker $workerId ready, waiting for jobs..." }
    log().info { "Redis pool stats: ${redisPool.stats()}" }

    var jobsProcessed = 0
    var failedJobs = 0

    while (true) {
        try {
            val job = jobQueue.dequeue()

            if (job != null) {
                jobsProcessed++
                val startTime = System.currentTimeMillis()

                log().info { "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" }
                log().info { "[$workerId] Job #$jobsProcessed received" }
                log().info { "   Type: ${job::class.simpleName}" }
                log().info { "   ID: ${job.id}" }
                log().info { "   Created: ${java.time.Instant.ofEpochMilli(job.createdAt)}" }

                try {
                    when (job) {
                        is CreateGroupJob -> {
                            log().info { "   Creating group: ${job.name}" }
                            log().info { "   Admin: ${job.adminId}" }
                            log().info { "   Members: ${job.memberIds.size}" }

                            val group = groupService.createGroup(
                                name = job.name,
                                description = job.description,
                                adminId = job.adminId,
                                memberIds = job.memberIds
                            )

                            log().info { "Group created: ${group.id}" }
                        }

                        is AddMemberJob -> {
                            log().info { "   Adding member ${job.userId} to group ${job.groupId}" }

                            val group = groupService.addMember(job.groupId, job.userId)

                            if (group != null) {
                                log().info { "Member added successfully" }
                            } else {
                                throw Exception("Failed to add member - group or user not found")
                            }
                        }

                        is RemoveMemberJob -> {
                            log().info { "   Removing member ${job.userId} from group ${job.groupId}" }

                            val group = groupService.removeMember(job.groupId, job.userId)

                            if (group != null) {
                                log().info { " Member removed successfully" }
                            } else {
                                throw Exception("Failed to remove member - group or user not found")
                            }
                        }
                    }

                    val processingTime = System.currentTimeMillis() - startTime
                    jobQueue.ack(job)
                    log().info { "    Processing time: ${processingTime}ms" }
                    log().info { "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n" }

                } catch (e: Exception) {
                    failedJobs++
                    log().error(e) { "Job failed (attempt ${getRetryCount(job.id)}/5): ${e.message}" }

                    val retryCount = getRetryCount(job.id)
                    val delaySeconds = when (retryCount) {
                        1 -> 5
                        2 -> 15
                        3 -> 45
                        4 -> 135
                        else -> 300
                    }

                    jobQueue.requeue(job, delaySeconds = delaySeconds.toLong())
                }
            } else {
                delay(100)
            }

        } catch (e: Exception) {
            log().error { "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" }
            log().error { " [$workerId] Error in worker loop" }
            log().error(e) { "   Error: ${e.message}" }
            log().error { "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n" }

            delay(1000)
        }
    }
}

private val retryCounts = mutableMapOf<String, Int>()

private fun getRetryCount(jobId: String): Int {
    return retryCounts.merge(jobId, 1) { old, _ -> old + 1 } ?: 1
}

private fun resetRetryCount(jobId: String) {
    retryCounts.remove(jobId)
}