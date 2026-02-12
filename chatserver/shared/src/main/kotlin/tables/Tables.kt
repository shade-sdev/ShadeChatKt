package tables

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone


object UsersTable : Table("users") {
    val id = uuid("id")
    val username = text("username").uniqueIndex()
    val displayName = text("display_name")
    val avatarUrl = text("avatar_url").nullable()
    val status = text("status")
    val passwordHash = text("password_hash")
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(id)
}

object GroupsTable : Table("groups") {
    val id = uuid("id")
    val name = text("name")
    val description = text("description").nullable()
    val adminId = uuid("admin_id").references(UsersTable.id)
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(id)
}

object GroupMembersTable : Table("group_members") {
    val groupId = uuid("group_id").references(GroupsTable.id)
    val userId = uuid("user_id").references(UsersTable.id)

    override val primaryKey = PrimaryKey(groupId, userId)
}

object DmConversationsTable : Table("dm_conversations") {
    val id = uuid("id")
    val participant1Id = uuid("participant1_id").references(UsersTable.id)
    val participant2Id = uuid("participant2_id").references(UsersTable.id)
    val createdAt = timestampWithTimeZone("created_at")

    init {
        uniqueIndex(participant1Id, participant2Id)
    }

    override val primaryKey = PrimaryKey(id)
}

object MessagesTable : Table("messages") {
    val id = uuid("id")
    val senderId = uuid("sender_id").references(UsersTable.id)

    val groupId = uuid("group_id").references(GroupsTable.id).nullable()
    val dmId = uuid("dm_id").references(DmConversationsTable.id).nullable()

    val content = text("content")
    val createdAt = timestampWithTimeZone("created_at")
    val editedAt = timestampWithTimeZone("edited_at").nullable()

    override val primaryKey = PrimaryKey(id)
}