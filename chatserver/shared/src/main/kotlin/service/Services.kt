package service

import auth.hashPassword
import auth.verifyPassword
import redis.RedisMessageBus
import redis.MessageQueue
import redis.ConnectionRegistry
import model.DirectMessageConversation
import model.Group
import model.Message
import model.User
import model.UserStatus
import repository.DMRepository
import repository.GroupRepository
import repository.MessageRepository
import repository.UserRepository
import dto.*
import kotlin.time.Clock
import java.util.*
import java.util.Base64

class UserService(
    private val userRepository: UserRepository,
    private val messageBus: RedisMessageBus
) {
    suspend fun register(username: String, password: String, displayName: String): User? {
        if (userRepository.findByUsername(username) != null) {
            return null
        }

        val user = User(
            id = UUID.randomUUID().toString(),
            username = username,
            displayName = displayName,
            passwordHash = hashPassword(password),
            createdAt = Clock.System.now(),
        )

        return userRepository.save(user)
    }

    suspend fun getAllUsers(): List<User> {
        return userRepository.findAll()
    }

    suspend fun authenticate(username: String, password: String): User? {
        val user = userRepository.findByUsername(username) ?: return null
        return if (verifyPassword(password, user.passwordHash)) user else null
    }

    suspend fun findById(id: String): User? = userRepository.findById(id)

    suspend fun updateUser(id: String, displayName: String?, avatarUrl: String?): User? {
        return userRepository.update(id) { user ->
            user.copy(
                displayName = displayName ?: user.displayName,
                avatarUrl = avatarUrl ?: user.avatarUrl
            )
        }
    }

    suspend fun updateStatus(userId: String, status: UserStatus) {
        println("📝 Updating status for user $userId to $status")
        userRepository.update(userId) { it.copy(status = status) }

        val statusUpdate = UserStatusUpdate(userId, status)
        println("📡 Broadcasting status update via Redis")
        messageBus.publishBroadcast(statusUpdate)
    }

    fun toResponse(user: User) = UserResponse(
        id = user.id,
        username = user.username,
        displayName = user.displayName,
        avatarUrl = user.avatarUrl,
        status = user.status,
        createdAt = user.createdAt
    )
}

class GroupService(
    private val groupRepository: GroupRepository,
    private val userRepository: UserRepository,
    private val messageBus: RedisMessageBus
) {
    /**
     * Create group - called by WORKER
     */
    suspend fun createGroup(name: String, description: String?, adminId: String, memberIds: List<String>): Group {
        val allMembers = (memberIds + adminId).distinct().toMutableList()

        val group = Group(
            id = UUID.randomUUID().toString(),
            name = name,
            description = description,
            adminId = adminId,
            memberIds = allMembers,
            createdAt = Clock.System.now()
        )

        val savedGroup = groupRepository.save(group)

        // Send invitations via Redis Pub/Sub
        allMembers.filter { it != adminId }.forEach { memberId ->
            val adminUser = userRepository.findById(adminId)
            val invitation = GroupInvitation(
                groupId = savedGroup.id,
                groupName = savedGroup.name,
                invitedBy = adminId,
                invitedByDisplayName = adminUser?.displayName ?: "Unknown"
            )
            messageBus.publishToUser(memberId, invitation)
        }

        val groupCreated = GroupUpdated(
            groupId = savedGroup.id,
            action = "group_created"
        )
        messageBus.publishToUser(adminId, groupCreated)

        return savedGroup
    }

    suspend fun findById(id: String): Group? = groupRepository.findById(id)

    suspend fun findByUserId(userId: String): List<Group> = groupRepository.findByUserId(userId)

    /**
     * Add member - called by WORKER
     */
    suspend fun addMember(groupId: String, userId: String): Group? {
        val group = groupRepository.findById(groupId) ?: return null

        if (userId in group.memberIds) {
            return group
        }

        val updatedGroup = groupRepository.update(groupId) { group ->
            if (userId !in group.memberIds) {
                group.memberIds.add(userId)
            }
            group
        }

        if (updatedGroup != null) {
            val adminUser = userRepository.findById(group.adminId)
            val invitation = GroupInvitation(
                groupId = groupId,
                groupName = group.name,
                invitedBy = group.adminId,
                invitedByDisplayName = adminUser?.displayName ?: "Unknown"
            )
            messageBus.publishToUser(userId, invitation)

            val addedUser = userRepository.findById(userId)
            val groupUpdated = GroupUpdated(
                groupId = groupId,
                action = "member_added",
                userId = userId,
                userName = addedUser?.displayName ?: "Unknown"
            )

            group.memberIds.forEach { memberId ->
                if (memberId != userId) {
                    messageBus.publishToUser(memberId, groupUpdated)
                }
            }
        }

        return updatedGroup
    }

    /**
     * Remove member - called by WORKER
     */
    suspend fun removeMember(groupId: String, userId: String): Group? {
        val group = groupRepository.findById(groupId) ?: return null

        if (userId !in group.memberIds) {
            return group
        }

        val updatedGroup = groupRepository.update(groupId) { group ->
            group.memberIds.remove(userId)
            group
        }

        if (updatedGroup != null) {
            val removedUser = userRepository.findById(userId)
            val groupUpdated = GroupUpdated(
                groupId = groupId,
                action = "member_removed",
                userId = userId,
                userName = removedUser?.displayName ?: "Unknown"
            )

            updatedGroup.memberIds.forEach { memberId ->
                messageBus.publishToUser(memberId, groupUpdated)
            }

            messageBus.publishToUser(userId, GroupUpdated(
                groupId = groupId,
                action = "removed_from_group"
            ))
        }

        return updatedGroup
    }

    suspend fun deleteGroup(groupId: String): Boolean {
        return groupRepository.delete(groupId)
    }

    fun toResponse(group: Group) = GroupResponse(
        id = group.id,
        name = group.name,
        description = group.description,
        adminId = group.adminId,
        memberIds = group.memberIds,
        createdAt = group.createdAt
    )
}

class MessageService(
    private val messageRepository: MessageRepository,
    private val userRepository: UserRepository,
    private val messageBus: RedisMessageBus,
    private val dmRepository: DMRepository,
    private val groupRepository: GroupRepository,
    private val messageQueue: MessageQueue,
    private val connectionRegistry: ConnectionRegistry
) {
    /**
     * Send message with offline queue support
     */
    suspend fun sendMessage(senderId: String, groupId: String?, dmId: String?, content: String): Message {
        val message = Message(
            id = UUID.randomUUID().toString(),
            senderId = senderId,
            groupId = groupId,
            dmId = dmId,
            content = content,
            createdAt = Clock.System.now()
        )

        messageRepository.save(message)

        val sender = userRepository.findById(senderId)
        val response = toResponse(message, sender?.displayName ?: "Unknown")
        val notification = NewMessageNotification(response)

        if (groupId != null) {
            sendToGroupWithQueue(groupId, notification)
        } else if (dmId != null) {
            sendToDMWithQueue(dmId, notification)
        }

        return message
    }

    /**
     * Send to group - queue for offline users
     */
    private suspend fun sendToGroupWithQueue(groupId: String, notification: NewMessageNotification) {
        val group = groupRepository.findById(groupId) ?: return
        val recipients = (group.memberIds + group.adminId).distinct()

        recipients.forEach { userId ->
            if (connectionRegistry.isUserOnline(userId)) {
                messageBus.publishToUser(userId, notification)
            } else {
                messageQueue.queueMessageForUser(userId, notification)
            }
        }
    }

    /**
     * Send to DM - queue for offline users
     */
    private suspend fun sendToDMWithQueue(dmId: String, notification: NewMessageNotification) {
        val dm = dmRepository.findById(dmId) ?: return

        listOf(dm.participant1Id, dm.participant2Id).forEach { userId ->
            if (connectionRegistry.isUserOnline(userId)) {
                messageBus.publishToUser(userId, notification)
            } else {
                messageQueue.queueMessageForUser(userId, notification)
            }
        }
    }

    /**
     * Cursor-based pagination
     */
    suspend fun getGroupMessages(groupId: String, cursor: String?, limit: Int = 50): PaginatedMessages {
        val afterMessageId = cursor?.let {
            try {
                String(Base64.getDecoder().decode(it))
            } catch (e: Exception) {
                null
            }
        }

        val messages = messageRepository.findByGroupIdCursor(groupId, afterMessageId, limit + 1)
        val hasMore = messages.size > limit
        val messageList = messages.take(limit)

        val messageResponses = messageList.map { msg ->
            val sender = userRepository.findById(msg.senderId)
            toResponse(msg, sender?.displayName ?: "Unknown")
        }

        val nextCursor = if (hasMore && messageList.isNotEmpty()) {
            Base64.getEncoder().encodeToString(messageList.last().id.toByteArray())
        } else {
            null
        }

        return PaginatedMessages(messageResponses, nextCursor, hasMore)
    }

    suspend fun getDMMessages(dmId: String, cursor: String?, limit: Int = 50): PaginatedMessages {
        val afterMessageId = cursor?.let {
            try {
                String(Base64.getDecoder().decode(it))
            } catch (e: Exception) {
                null
            }
        }

        val messages = messageRepository.findByDmIdCursor(dmId, afterMessageId, limit + 1)
        val hasMore = messages.size > limit
        val messageList = messages.take(limit)

        val messageResponses = messageList.map { msg ->
            val sender = userRepository.findById(msg.senderId)
            toResponse(msg, sender?.displayName ?: "Unknown")
        }

        val nextCursor = if (hasMore && messageList.isNotEmpty()) {
            Base64.getEncoder().encodeToString(messageList.last().id.toByteArray())
        } else {
            null
        }

        return PaginatedMessages(messageResponses, nextCursor, hasMore)
    }

    suspend fun editMessage(messageId: String, userId: String, content: String): Message? {
        val message = messageRepository.findById(messageId) ?: return null
        if (message.senderId != userId) return null

        return messageRepository.update(messageId) {
            it.copy(content = content, editedAt = Clock.System.now())
        }
    }

    suspend fun deleteMessage(messageId: String, userId: String): Boolean {
        val message = messageRepository.findById(messageId) ?: return false
        if (message.senderId != userId) return false

        return messageRepository.delete(messageId)
    }

    private fun toResponse(message: Message, senderName: String) = MessageResponse(
        id = message.id,
        senderId = message.senderId,
        senderName = senderName,
        groupId = message.groupId,
        dmId = message.dmId,
        content = message.content,
        createdAt = message.createdAt,
        editedAt = message.editedAt
    )
}

class DMService(
    private val dmRepository: DMRepository,
    private val userRepository: UserRepository
) {
    suspend fun createOrGetConversation(user1Id: String, user2Id: String): DirectMessageConversation {
        val existing = dmRepository.findByParticipants(user1Id, user2Id)
        if (existing != null) {
            return existing
        }

        val dm = DirectMessageConversation(
            id = UUID.randomUUID().toString(),
            participant1Id = user1Id,
            participant2Id = user2Id,
            createdAt = Clock.System.now()
        )

        return dmRepository.save(dm)
    }

    suspend fun findById(id: String): DirectMessageConversation? = dmRepository.findById(id)

    suspend fun findByUserId(userId: String): List<DirectMessageConversation> {
        return dmRepository.findByUserId(userId)
    }

    suspend fun toResponse(dm: DirectMessageConversation, currentUserId: String): DMConversationResponse {
        val otherUserId = if (dm.participant1Id == currentUserId) dm.participant2Id else dm.participant1Id
        val otherUser = userRepository.findById(otherUserId)!!

        return DMConversationResponse(
            id = dm.id,
            participant1Id = dm.participant1Id,
            participant2Id = dm.participant2Id,
            otherUser = UserResponse(
                id = otherUser.id,
                username = otherUser.username,
                displayName = otherUser.displayName,
                avatarUrl = otherUser.avatarUrl,
                status = otherUser.status,
                createdAt = otherUser.createdAt
            ),
            createdAt = dm.createdAt
        )
    }
}