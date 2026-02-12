import database.DatabaseFactory
import jobs.*
import kotlinx.coroutines.runBlocking
import redis.JobQueue
import redis.RedisMessageBus
import repository.*
import service.*
import util.log

/**
 * Worker - processes background jobs
 * Pulls jobs from Redis queue and executes them
 */
fun main() = runBlocking {
    val workerId = System.getenv("WORKER_ID") ?: "worker-1"
    log().info { "Starting Worker: $workerId"}

    DatabaseFactory.init(false)

    val redisUrl = System.getenv("REDIS_URL") ?: "redis://localhost:6379"
    log().info { "Connecting to Redis: $redisUrl"}

    val jobQueue = JobQueue(redisUrl)

    val userRepository = UserRepository()
    val groupRepository = GroupRepository()

    val messageBus = RedisMessageBus(redisUrl, NoOpLocalDelivery())

    val groupService = GroupService(groupRepository, userRepository, messageBus)

    log().info { "Worker $workerId ready, waiting for jobs..."}

    var jobsProcessed = 0

    while (true) {
        try {
            val job = jobQueue.dequeue()

            if (job != null) {
                jobsProcessed++
                log().info { "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"}
                log().info { "📦 [$workerId] Job #$jobsProcessed received"}
                log().info { "   Type: ${job::class.simpleName}"}
                log().info { "   ID: ${job.id}"}
                log().info { "   Created: ${java.time.Instant.ofEpochMilli(job.createdAt)}"}

                when (job) {
                    is CreateGroupJob -> {
                        log().info { "   Creating group: ${job.name}"}
                        log().info { "   Admin: ${job.adminId}"}
                        log().info { "   Members: ${job.memberIds.size}"}

                        val group = groupService.createGroup(
                            name = job.name,
                            description = job.description,
                            adminId = job.adminId,
                            memberIds = job.memberIds
                        )

                        log().info { "✅ Group created: ${group.id}"}
                        log().info { "   Notifications sent via Redis Pub/Sub"}
                    }

                    is AddMemberJob -> {
                        log().info { "   Adding member ${job.userId} to group ${job.groupId}"}

                        val group = groupService.addMember(job.groupId, job.userId)

                        if (group != null) {
                            log().info { "✅ Member added successfully"}
                            log().info { "   Notifications sent via Redis Pub/Sub"}
                        } else {
                            log().error { "Failed to add member"}
                        }
                    }

                    is RemoveMemberJob -> {
                        log().info { "   Removing member ${job.userId} from group ${job.groupId}"}

                        val group = groupService.removeMember(job.groupId, job.userId)

                        if (group != null) {
                            log().info { "✅ Member removed successfully"}
                            log().info { "   Notifications sent via Redis Pub/Sub"}
                        } else {
                            log().error { "Failed to remove member"}
                        }
                    }
                }

                log().info { "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"}
            }
        } catch (e: Exception) {
            log().error{"━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"}
            log().error{"❌ [$workerId] Error processing job"}
            log().error(e) {"   Error: ${e.message}"}
            e.printStackTrace()
            log().error{"━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"}
        }
    }
}