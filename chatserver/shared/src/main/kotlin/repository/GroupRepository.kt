package repository

import database.dbQuery
import kotlin.time.Instant
import model.Group
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import tables.GroupMembersTable
import tables.GroupsTable
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.*
import kotlin.time.toJavaInstant


class GroupRepository {

    suspend fun save(group: Group): Group = dbQuery {
        GroupsTable.insert {
            it[id] = UUID.fromString(group.id)
            it[name] = group.name
            it[description] = group.description
            it[adminId] = UUID.fromString(group.adminId)
            it[createdAt] = OffsetDateTime.ofInstant(group.createdAt.toJavaInstant(), ZoneOffset.UTC)
        }

        // members
        group.memberIds.forEach { memberId ->
            GroupMembersTable.insertIgnore {
                it[groupId] = UUID.fromString(group.id)
                it[userId] = UUID.fromString(memberId)
            }
        }

        group
    }

    suspend fun findById(id: String): Group? = dbQuery {
        val groupRow = GroupsTable.selectAll()
            .where { GroupsTable.id eq UUID.fromString(id) }
            .limit(1)
            .singleOrNull() ?: return@dbQuery null

        val members = GroupMembersTable
            .selectAll()
            .where { GroupMembersTable.groupId eq UUID.fromString(id) }
            .map { it[GroupMembersTable.userId].toString() }
            .toMutableList()

        Group(
            id = groupRow[GroupsTable.id].toString(),
            name = groupRow[GroupsTable.name],
            description = groupRow[GroupsTable.description],
            adminId = groupRow[GroupsTable.adminId].toString(),
            memberIds = members,
            createdAt = Instant.fromEpochMilliseconds(groupRow[GroupsTable.createdAt].toEpochSecond() * 1000)
        )
    }

    suspend fun findByUserId(userId: String): List<Group> = dbQuery {
        val groupIds = GroupMembersTable
            .select(GroupMembersTable.groupId)
            .where { GroupMembersTable.userId eq UUID.fromString(userId) }
            .map { it[GroupMembersTable.groupId] }

        groupIds.mapNotNull { gid -> findById(gid.toString()) }
    }

    suspend fun update(id: String, updateFn: (Group) -> Group): Group? = dbQuery {
        val existing = findById(id) ?: return@dbQuery null
        val updated = updateFn(existing)

        GroupsTable.update({ GroupsTable.id eq UUID.fromString(id) }) {
            it[name] = updated.name
            it[description] = updated.description
        }

        GroupMembersTable.deleteWhere { GroupMembersTable.groupId eq UUID.fromString(id) }

        updated.memberIds.forEach { memberId ->
            GroupMembersTable.insertIgnore {
                it[groupId] = UUID.fromString(id)
                it[userId] = UUID.fromString(memberId)
            }
        }

        updated
    }

    suspend fun delete(id: String): Boolean = dbQuery {
        GroupsTable.deleteWhere { GroupsTable.id eq UUID.fromString(id) } > 0
    }
}