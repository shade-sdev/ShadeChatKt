package auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.auth.Principal
import model.User
import java.security.MessageDigest
import java.util.*

object JWTConfig {
    private val SECRET = System.getenv("JWT_SECRET") ?: "dev-secret-key-do-not-use-in-prod"
    private const val ISSUER = "chat-app"
    private const val VALIDITY_MS = 36_000_000 * 24 * 30 // 30 days

    private val algorithm = Algorithm.HMAC256(SECRET)

    fun generateToken(userId: String): String {
        return JWT.create()
            .withIssuer(ISSUER)
            .withClaim("userId", userId)
            .withExpiresAt(Date(System.currentTimeMillis() + VALIDITY_MS))
            .sign(algorithm)
    }

    fun verifyToken(token: String): String? {
        return try {
            val verifier = JWT.require(algorithm)
                .withIssuer(ISSUER)
                .build()
            val jwt = verifier.verify(token)
            jwt.getClaim("userId").asString()
        } catch (e: Exception) {
            null
        }
    }
}

fun hashPassword(password: String): String {
    val bytes = password.toByteArray()
    val md = MessageDigest.getInstance("SHA-256")
    val digest = md.digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
}

fun verifyPassword(password: String, hash: String): Boolean {
    return hashPassword(password) == hash
}

data class UserPrincipal(
    val user: User
) : Principal