package repository

import database.dbQuery
import kotlin.time.Instant
import model.Message
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import tables.MessagesTable
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.*
import kotlin.time.toJavaInstant

class MessageRepository {

    suspend fun save(message: Message): Message = dbQuery {
        MessagesTable.insert {
            it[id] = UUID.fromString(message.id)
            it[senderId] = UUID.fromString(message.senderId)
            it[groupId] = message.groupId?.let(UUID::fromString)
            it[dmId] = message.dmId?.let(UUID::fromString)
            it[content] = message.content
            it[createdAt] = OffsetDateTime.ofInstant(message.createdAt.toJavaInstant(), ZoneOffset.UTC)
            it[editedAt] = message.editedAt?.let { instant ->
                OffsetDateTime.ofInstant(instant.toJavaInstant(), ZoneOffset.UTC)
            }
        }
        message
    }


    suspend fun findById(id: String): Message? = dbQuery {
        MessagesTable.selectAll()
            .where { MessagesTable.id eq UUID.fromString(id) }
            .limit(1)
            .map(::toMessage)
            .singleOrNull()
    }

    suspend fun findByGroupIdCursor(groupId: String, afterMessageId: String?, limit: Int): List<Message> = dbQuery {
        val gid = UUID.fromString(groupId)

        if (afterMessageId == null) {
            return@dbQuery MessagesTable.selectAll()
                .where { MessagesTable.groupId eq gid }
                .orderBy(MessagesTable.createdAt, SortOrder.DESC)
                .limit(limit)
                .map(::toMessage)
        }

        val cursor = findById(afterMessageId) ?: return@dbQuery MessagesTable.selectAll()
            .where { MessagesTable.groupId eq gid }
            .orderBy(MessagesTable.createdAt, SortOrder.DESC)
            .limit(limit)
            .map(::toMessage)

        MessagesTable.selectAll()
            .where {
                (MessagesTable.groupId eq gid) and (MessagesTable.createdAt less OffsetDateTime.ofInstant(cursor.createdAt.toJavaInstant(), ZoneOffset.UTC))
            }
            .orderBy(MessagesTable.createdAt, SortOrder.DESC)
            .limit(limit)
            .map(::toMessage)
    }

    suspend fun findByDmIdCursor(dmId: String, afterMessageId: String?, limit: Int): List<Message> = dbQuery {
        val did = UUID.fromString(dmId)

        if (afterMessageId == null) {
            return@dbQuery MessagesTable.selectAll()
                .where { MessagesTable.dmId eq did }
                .orderBy(MessagesTable.createdAt, SortOrder.DESC)
                .limit(limit)
                .map(::toMessage)
        }

        val cursor = findById(afterMessageId) ?: return@dbQuery MessagesTable.selectAll()
            .where { MessagesTable.dmId eq did }
            .orderBy(MessagesTable.createdAt, SortOrder.DESC)
            .limit(limit)
            .map(::toMessage)

        MessagesTable.selectAll()
            .where {
                (MessagesTable.dmId eq did) and (MessagesTable.createdAt less OffsetDateTime.ofInstant(cursor.createdAt.toJavaInstant(), ZoneOffset.UTC))
            }
            .orderBy(MessagesTable.createdAt, SortOrder.DESC)
            .limit(limit)
            .map(::toMessage)
    }

    suspend fun update(id: String, updateFn: (Message) -> Message): Message? = dbQuery {
        val existing = findById(id) ?: return@dbQuery null
        val updated = updateFn(existing)

        MessagesTable.update({ MessagesTable.id eq UUID.fromString(id) }) {
            it[content] = updated.content
            it[editedAt] = OffsetDateTime.ofInstant(updated.editedAt?.toJavaInstant(), ZoneOffset.UTC)
        }

        updated
    }

    suspend fun delete(id: String): Boolean = dbQuery {
        MessagesTable.deleteWhere { MessagesTable.id eq UUID.fromString(id) } > 0
    }

    private fun toMessage(row: ResultRow): Message {
        return Message(
            id = row[MessagesTable.id].toString(),
            senderId = row[MessagesTable.senderId].toString(),
            groupId = row[MessagesTable.groupId]?.toString(),
            dmId = row[MessagesTable.dmId]?.toString(),
            content = row[MessagesTable.content],
            createdAt = Instant.fromEpochMilliseconds(row[MessagesTable.createdAt].toEpochSecond() * 1000),
            editedAt = row[MessagesTable.editedAt]?.let { javaInstant ->
                Instant.fromEpochMilliseconds(javaInstant.toEpochSecond() * 1000)
            }
        )
    }
}