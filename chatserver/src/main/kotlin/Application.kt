import auth.UserPrincipal
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.defaultResource
import io.ktor.server.http.content.resources
import io.ktor.server.http.content.static
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import repository.DMRepository
import repository.GroupRepository
import repository.MessageRepository
import repository.UserRepository
import routes.*
import service.DMService
import service.GroupService
import service.MessageService
import service.UserService
import websocket.WebSocketConnectionManager
import java.time.Duration


fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    configureAppSync()
}

fun Application.configureAppSync() {
    val scope = CoroutineScope(Dispatchers.IO)
    scope.launch {
        configureApp()
    }
}

suspend fun Application.configureApp() {

    // ========================================
    // Repositories - Data layer
    // ========================================
    val userRepository = UserRepository()
    val groupRepository = GroupRepository()
    val messageRepository = MessageRepository()
    val dmRepository = DMRepository()

    // Initialize test data
    initTestData(userRepository, dmRepository)

    // ========================================
    // WebSocket Connection Manager
    // ========================================
    val wsManager = WebSocketConnectionManager()

    // ========================================
    // Services - Business logic layer
    // ========================================
    val userService = UserService(userRepository, wsManager)
    val groupService = GroupService(groupRepository, userRepository, wsManager)
    val messageService = MessageService(
        messageRepository = messageRepository,
        userRepository = userRepository,
        wsManager = wsManager,
        dmRepository = dmRepository,  // Add this
        groupRepository = groupRepository  // Add this
    )
    val dmService = DMService(dmRepository, userRepository)

    // ========================================
    // Plugins Configuration
    // ========================================

    // JSON serialization
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
        })
    }

    // CORS configuration for web clients
    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Patch)

        // WebSocket headers
        allowHeader("Sec-WebSocket-Key")
        allowHeader("Sec-WebSocket-Version")
        allowHeader("Sec-WebSocket-Extensions")
        allowHeader("Sec-WebSocket-Protocol")

        allowCredentials = true
    }

    // WebSocket configuration
    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(15)
        timeout = Duration.ofSeconds(15)
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }

    // JWT Authentication
    install(Authentication) {
        jwt {
            verifier(
                JWT.require(Algorithm.HMAC256(System.getenv("JWT_SECRET") ?: "dev-secret-key-do-not-use-in-prod"))
                    .withIssuer("chat-app")
                    .build()
            )

            validate { credential ->
                val userId = credential.payload
                    .getClaim("userId")
                    .asString()
                    ?: return@validate null

                val user = userService.findById(userId)
                    ?: return@validate null

                UserPrincipal(user)
            }

            challenge { _, _ ->
                call.respond(
                    HttpStatusCode.Unauthorized,
                    mapOf("error" to "Invalid or expired token")
                )
            }
        }
    }

    // ========================================
    // Routing Configuration
    // ========================================
    routing {
        staticResources("/", "") {
            default("client.html")
        }

        // API Routes
        authRoutes(userService)
        userRoutes(userService)
        groupRoutes(groupService, messageService)
        dmRoutes(dmService, messageService)
        websocketRoute(wsManager, userService, dmRepository, groupRepository)

        // Health check endpoint
        get("/health") {
            call.respondText("OK", ContentType.Text.Plain)
        }
    }
}

/**
 * Initialize test data for development
 * Creates test users and a sample DM conversation
 */
private suspend fun initTestData(userRepository: UserRepository, dmRepository: DMRepository) {
    if (userRepository.findAll().isEmpty()) {
        println("Creating test users...")

        val user1 = model.User(
            id = "user1",
            username = "alice",
            displayName = "Alice",
            passwordHash = auth.hashPassword("password"),
            createdAt = kotlinx.datetime.Clock.System.now()
        )

        val user2 = model.User(
            id = "user2",
            username = "bob",
            displayName = "Bob",
            passwordHash = auth.hashPassword("password"),
            createdAt = kotlinx.datetime.Clock.System.now()
        )

        userRepository.save(user1)
        userRepository.save(user2)

        // Create a test DM conversation
        val dm = model.DirectMessageConversation(
            id = "dm1",
            participant1Id = "user1",
            participant2Id = "user2",
            createdAt = kotlinx.datetime.Clock.System.now()
        )
        dmRepository.save(dm)

        println("Test users created:")
        println("  alice / password")
        println("  bob / password")
    }
}