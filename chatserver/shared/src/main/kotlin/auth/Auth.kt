package auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import model.User
import org.mindrot.jbcrypt.BCrypt
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

/**
 * Secure password hashing using BCrypt
 * Work factor 12 = ~300ms to hash (prevents brute force attacks)
 */
fun hashPassword(password: String): String {
    return BCrypt.hashpw(password, BCrypt.gensalt(12))
}

/**
 * Verify password against BCrypt hash
 * Constant-time comparison prevents timing attacks
 */
fun verifyPassword(password: String, hash: String): Boolean {
    return try {
        BCrypt.checkpw(password, hash)
    } catch (e: Exception) {
        false
    }
}

data class UserPrincipal(
    val user: User
)