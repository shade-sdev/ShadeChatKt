package repository


import database.dbQuery
import model.User
import model.UserStatus
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import tables.UsersTable
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.*
import kotlin.time.Instant
import kotlin.time.toJavaInstant

class UserRepository {

    suspend fun save(user: User): User = dbQuery {
        UsersTable.insert {
            it[id] = UUID.fromString(user.id)
            it[username] = user.username
            it[displayName] = user.displayName
            it[avatarUrl] = user.avatarUrl
            it[status] = user.status.name
            it[passwordHash] = user.passwordHash
            it[createdAt] = OffsetDateTime.ofInstant(user.createdAt.toJavaInstant(), ZoneOffset.UTC)
        }
        user
    }

    suspend fun findById(id: String): User? = dbQuery {
        UsersTable.selectAll()
            .where { UsersTable.id eq UUID.fromString(id) }
            .limit(1)
            .map(::toUser)
            .singleOrNull()
    }

    suspend fun findByUsername(username: String): User? = dbQuery {
        UsersTable.selectAll()
            .where { UsersTable.username eq username }
            .limit(1)
            .map(::toUser)
            .singleOrNull()
    }

    suspend fun findAll(): List<User> = dbQuery {
        UsersTable.selectAll()
            .map(::toUser)
    }

    suspend fun update(id: String, updateFn: (User) -> User): User? = dbQuery {
        val existing = findById(id) ?: return@dbQuery null
        val updated = updateFn(existing)

        UsersTable.update({ UsersTable.id eq UUID.fromString(id) }) {
            it[username] = updated.username
            it[displayName] = updated.displayName
            it[avatarUrl] = updated.avatarUrl
            it[status] = updated.status.name
            it[passwordHash] = updated.passwordHash
        }

        updated
    }

    private fun toUser(row: ResultRow): User {
        return User(
            id = row[UsersTable.id].toString(),
            username = row[UsersTable.username],
            displayName = row[UsersTable.displayName],
            avatarUrl = row[UsersTable.avatarUrl],
            status = UserStatus.valueOf(row[UsersTable.status]),
            passwordHash = row[UsersTable.passwordHash],
            createdAt = Instant.fromEpochMilliseconds(row[UsersTable.createdAt].toEpochSecond() * 1000)
        )
    }
}
