import database.DatabaseFactory
import jobs.*
import kotlinx.coroutines.runBlocking
import redis.JobQueue
import redis.RedisMessageBus
import repository.*
import service.*

/**
 * Worker - processes background jobs
 * Pulls jobs from Redis queue and executes them
 */
fun main() = runBlocking {
    val workerId = System.getenv("WORKER_ID") ?: "worker-1"
    println("🔧 Starting Worker: $workerId")

    DatabaseFactory.init(false)

    val redisUrl = System.getenv("REDIS_URL") ?: "redis://localhost:6379"
    println("📡 Connecting to Redis: $redisUrl")

    val jobQueue = JobQueue(redisUrl)

    // Initialize repositories (you will replace with PostgreSQL)
    val userRepository = UserRepository()
    val groupRepository = GroupRepository()

    // Worker uses no-op delivery because it has no WebSocket connections
    // All real-time messages go through Redis Pub/Sub to API servers
    val messageBus = RedisMessageBus(redisUrl, NoOpLocalDelivery())

    val groupService = GroupService(groupRepository, userRepository, messageBus)

    println("✅ Worker $workerId ready, waiting for jobs...")
    println("📋 Press Ctrl+C to stop worker\n")

    var jobsProcessed = 0

    while (true) {
        try {
            val job = jobQueue.dequeue()

            if (job != null) {
                jobsProcessed++
                println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                println("📦 [$workerId] Job #$jobsProcessed received")
                println("   Type: ${job::class.simpleName}")
                println("   ID: ${job.id}")
                println("   Created: ${java.time.Instant.ofEpochMilli(job.createdAt)}")

                when (job) {
                    is CreateGroupJob -> {
                        println("   Creating group: ${job.name}")
                        println("   Admin: ${job.adminId}")
                        println("   Members: ${job.memberIds.size}")

                        val group = groupService.createGroup(
                            name = job.name,
                            description = job.description,
                            adminId = job.adminId,
                            memberIds = job.memberIds
                        )

                        println("✅ Group created: ${group.id}")
                        println("   Notifications sent via Redis Pub/Sub")
                    }

                    is AddMemberJob -> {
                        println("   Adding member ${job.userId} to group ${job.groupId}")

                        val group = groupService.addMember(job.groupId, job.userId)

                        if (group != null) {
                            println("✅ Member added successfully")
                            println("   Notifications sent via Redis Pub/Sub")
                        } else {
                            println("❌ Failed to add member")
                        }
                    }

                    is RemoveMemberJob -> {
                        println("   Removing member ${job.userId} from group ${job.groupId}")

                        val group = groupService.removeMember(job.groupId, job.userId)

                        if (group != null) {
                            println("✅ Member removed successfully")
                            println("   Notifications sent via Redis Pub/Sub")
                        } else {
                            println("❌ Failed to remove member")
                        }
                    }
                }

                println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")
            }
        } catch (e: Exception) {
            println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            println("❌ [$workerId] Error processing job")
            println("   Error: ${e.message}")
            e.printStackTrace()
            println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")
        }
    }
}