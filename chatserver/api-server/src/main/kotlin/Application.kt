import auth.UserPrincipal
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import database.DatabaseFactory
import dto.ErrorResponse
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import redis.ConnectionRegistry
import redis.JobQueue
import redis.MessageQueue
import redis.RedisMessageBus
import repository.DMRepository
import repository.GroupRepository
import repository.MessageRepository
import repository.UserRepository
import routes.*
import service.DMService
import service.GroupService
import service.MessageService
import service.UserService
import util.log
import websocket.WebSocketConnectionManager
import java.util.*
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    runBlocking {
        configureApp()
    }
}

suspend fun Application.configureApp() {
    val serverId = System.getenv("SERVER_ID") ?: "api-server-1"
    val jwtSecret = System.getenv("JWT_SECRET") ?: "dev-secret-key-do-not-use-in-prod"
    val redisUrl = System.getenv("REDIS_URL") ?: "redis://localhost:6379"

    // 1. Core Infrastructure
    DatabaseFactory.init()

    val userRepository = UserRepository()
    val groupRepository = GroupRepository()
    val messageRepository = MessageRepository()
    val dmRepository = DMRepository()
    initTestData(userRepository, dmRepository)

    // 2. Redis & Connection Management
    val wsManager = WebSocketConnectionManager().apply { setGroupRepository(groupRepository) }
    val messageBus = RedisMessageBus(redisUrl, wsManager)
    val jobQueue = JobQueue(redisUrl)
    val messageQueue = MessageQueue(redisUrl)
    val connectionRegistry = ConnectionRegistry(redisUrl)

    // Heartbeat logic
    val heartbeatJob = launch(Dispatchers.IO) {
        while (isActive) {
            delay(30_000)
            try {
                connectionRegistry.heartbeat()
            } catch (ex: Exception) {
                log().error(ex) { "Heartbeat error" }
            }
        }
    }

    // 3. Services
    val userService = UserService(userRepository, messageBus)
    val groupService = GroupService(groupRepository, userRepository, messageBus)
    val messageService = MessageService(messageRepository, userRepository, messageBus, dmRepository, groupRepository, messageQueue, connectionRegistry)
    val dmService = DMService(dmRepository, userRepository)

    // 4. Plugins
    install(ContentNegotiation) {
        json(Json { prettyPrint = true; isLenient = true; ignoreUnknownKeys = true })
    }

    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowHeader("Sec-WebSocket-Protocol")
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Patch)
        allowCredentials = true
    }

    install(RateLimit) {
        register(RateLimitName("write")) {
            rateLimiter(limit = 60, refillPeriod = 1.minutes)

            requestKey { call ->
                call.principal<UserPrincipal>()?.user?.id ?: call.request.origin.remoteAddress
            }

            modifyResponse { call, state ->
                when (state) {
                    is RateLimiter.State.Available -> {
                        call.response.headers.append("X-RateLimit-Limit", state.limit.toString())
                        call.response.headers.append("X-RateLimit-Remaining", state.remainingTokens.toString())
                        call.response.headers.append("X-RateLimit-Reset", (state.refillAtTimeMillis / 1000).toString())
                    }

                    is RateLimiter.State.Exhausted -> {
                        val retryAfterSeconds = state.toWait.inWholeSeconds
                        call.response.headers.append("Retry-After", retryAfterSeconds.toString())
                        log().info { "Rate limit exhausted for ${call.request.origin.remoteAddress}. Retry in ${retryAfterSeconds}s" }
                    }
                }
            }
        }

        register(RateLimitName("auth")) {
            rateLimiter(limit = 5, refillPeriod = 30.seconds)
            requestKey { call -> call.request.origin.remoteAddress }
        }

        global {
            rateLimiter(limit = 500, refillPeriod = 1.minutes)
            requestKey { call -> call.request.origin.remoteAddress }
        }
    }

    install(WebSockets) {
        pingPeriod = 30.seconds
        timeout = 15.seconds
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }

    install(Authentication) {
        jwt {
            verifier(JWT.require(Algorithm.HMAC256(jwtSecret)).withIssuer("chat-app").build())
            validate { credential ->
                val userId = credential.payload.getClaim("userId").asString() ?: return@validate null
                userService.findById(userId)?.let { UserPrincipal(it) }
            }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid or expired token"))
            }
        }
    }

    // 5. Routing
    routing {
        staticResources("/", "") { default("client.html") }

        systemRoutes(serverId)
        authRoutes(userService)
        userRoutes(userService)
        groupRoutes(groupService, messageService, jobQueue)
        dmRoutes(dmService, messageService)
        websocketRoute(wsManager, userService, dmRepository, groupRepository, messageBus, messageQueue, connectionRegistry)
    }

    // 6. Lifecycle Management (Updated for Ktor 3)
    monitor.subscribe(ApplicationStopped) {
        heartbeatJob.cancel()
        log().info { "Server $serverId stopped." }
    }

    log().info { "API Server $serverId ready on port ${engine.environment.config.port}" }
}

private suspend fun initTestData(userRepository: UserRepository, dmRepository: DMRepository) {
    if (userRepository.findAll().isEmpty()) {

        val userIdAlice = UUID.randomUUID().toString()
        val userIdBob = UUID.randomUUID().toString()


        val user1 = model.User(
            id = userIdAlice,
            username = "alice",
            displayName = "Alice",
            passwordHash = auth.hashPassword("password"),
            createdAt = kotlin.time.Clock.System.now()
        )

        val user2 = model.User(
            id = userIdBob,
            username = "bob",
            displayName = "Bob",
            passwordHash = auth.hashPassword("password"),
            createdAt = kotlin.time.Clock.System.now()
        )

        userRepository.save(user1)
        userRepository.save(user2)

        val dm = model.DirectMessageConversation(
            id = UUID.randomUUID().toString(),
            participant1Id = userIdAlice,
            participant2Id = userIdBob,
            createdAt = kotlin.time.Clock.System.now()
        )

        dmRepository.save(dm)
    }
}