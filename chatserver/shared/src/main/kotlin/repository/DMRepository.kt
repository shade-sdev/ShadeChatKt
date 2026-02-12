package repository

import database.dbQuery
import model.DirectMessageConversation
import org.jetbrains.exposed.sql.*
import tables.DmConversationsTable
import tables.DmConversationsTable.createdAt
import tables.DmConversationsTable.id
import tables.DmConversationsTable.participant1Id
import tables.DmConversationsTable.participant2Id
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.*
import kotlin.time.toJavaInstant

class DMRepository {

    suspend fun save(dm: DirectMessageConversation): DirectMessageConversation = dbQuery {
        val p1 = UUID.fromString(dm.participant1Id)
        val p2 = UUID.fromString(dm.participant2Id)

        // store canonical ordering so uniqueness works
        val (a, b) = if (p1.toString() < p2.toString()) p1 to p2 else p2 to p1

        DmConversationsTable.insert {
            it[id] = UUID.fromString(dm.id)
            it[participant1Id] = a
            it[participant2Id] = b
            it[createdAt] = OffsetDateTime.ofInstant(dm.createdAt.toJavaInstant(), ZoneOffset.UTC)
        }

        dm.copy(participant1Id = a.toString(), participant2Id = b.toString())
    }

    suspend fun findById(id: String): DirectMessageConversation? = dbQuery {
        DmConversationsTable.selectAll()
            .where { DmConversationsTable.id eq UUID.fromString(id) }
            .limit(1)
            .map(::toDm)
            .singleOrNull()
    }

    suspend fun findByParticipants(user1Id: String, user2Id: String): DirectMessageConversation? = dbQuery {
        val p1 = UUID.fromString(user1Id)
        val p2 = UUID.fromString(user2Id)
        val (a, b) = if (p1.toString() < p2.toString()) p1 to p2 else p2 to p1

        DmConversationsTable.selectAll()
            .where { (participant1Id eq a) and (participant2Id eq b) }
            .limit(1)
            .map(::toDm)
            .singleOrNull()
    }

    suspend fun findByUserId(userId: String): List<DirectMessageConversation> = dbQuery {
        val uid = UUID.fromString(userId)

        DmConversationsTable.selectAll()
            .where { (participant1Id eq uid) or (participant2Id eq uid) }
            .map(::toDm)
    }

    private fun toDm(row: ResultRow): DirectMessageConversation {
        return DirectMessageConversation(
            id = row[id].toString(),
            participant1Id = row[participant1Id].toString(),
            participant2Id = row[participant2Id].toString(),
            createdAt = kotlin.time.Instant.fromEpochMilliseconds(row[createdAt].toEpochSecond() * 1000)
        )
    }
}