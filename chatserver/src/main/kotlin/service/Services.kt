package service

import auth.hashPassword
import auth.verifyPassword
import websocket.WebSocketConnectionManager
import model.DirectMessageConversation
import model.Group
import model.Message
import model.User
import model.UserStatus
import repository.DMRepository
import repository.GroupRepository
import repository.MessageRepository
import repository.UserRepository
import dto.DMConversationResponse
import dto.GroupInvitation
import dto.GroupResponse
import dto.GroupUpdated
import dto.MessageResponse
import dto.NewMessageNotification
import dto.PaginatedMessages
import dto.UserResponse
import dto.UserStatusUpdate
import kotlinx.datetime.Clock
import java.util.*

class UserService(
    private val userRepository: UserRepository,
    private val wsManager: WebSocketConnectionManager
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
            createdAt = Clock.System.now()
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
        println("Updating status for user $userId to $status")
        userRepository.update(userId) { it.copy(status = status) }

        val statusUpdate = UserStatusUpdate(userId, status)
        println("Broadcasting status update: $statusUpdate")
        wsManager.broadcast(statusUpdate)
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
    private val wsManager: WebSocketConnectionManager
) {
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

        allMembers.filter { it != adminId }.forEach { memberId ->
            val adminUser = userRepository.findById(adminId)
            val invitation = GroupInvitation(
                groupId = savedGroup.id,
                groupName = savedGroup.name,
                invitedBy = adminId,
                invitedByDisplayName = adminUser?.displayName ?: "Unknown"
            )
            wsManager.sendToUser(memberId, invitation)  // This is correct
        }

        // Send group created notification to admin
        val groupCreated = GroupUpdated(
            groupId = savedGroup.id,
            action = "group_created"
        )
        wsManager.sendToUser(adminId, groupCreated)

        return savedGroup
    }

    suspend fun findById(id: String): Group? = groupRepository.findById(id)

    suspend fun findByUserId(userId: String): List<Group> = groupRepository.findByUserId(userId)

    suspend fun addMember(groupId: String, userId: String): Group? {
        val group = groupRepository.findById(groupId) ?: return null

        // Check if user is already a member
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
            // Send group invitation to the new member
            val adminUser = userRepository.findById(group.adminId)
            val invitation = GroupInvitation(
                groupId = groupId,
                groupName = group.name,
                invitedBy = group.adminId,
                invitedByDisplayName = adminUser?.displayName ?: "Unknown"
            )
            wsManager.sendToUser(userId, invitation)

            // Send group updated notification to all existing members
            val addedUser = userRepository.findById(userId)
            val groupUpdated = GroupUpdated(
                groupId = groupId,
                action = "member_added",
                userId = userId,
                userName = addedUser?.displayName ?: "Unknown"
            )

            // Send to all existing members except the newly added user
            group.memberIds.forEach { memberId ->
                if (memberId != userId) {
                    wsManager.sendToUser(memberId, groupUpdated)
                }
            }
        }

        return updatedGroup
    }

    suspend fun removeMember(groupId: String, userId: String): Group? {
        val group = groupRepository.findById(groupId) ?: return null

        // Check if user is a member
        if (userId !in group.memberIds) {
            return group
        }

        val updatedGroup = groupRepository.update(groupId) { group ->
            group.memberIds.remove(userId)
            group
        }

        if (updatedGroup != null) {
            // Send group updated notification to all remaining members
            val removedUser = userRepository.findById(userId)
            val groupUpdated = GroupUpdated(
                groupId = groupId,
                action = "member_removed",
                userId = userId,
                userName = removedUser?.displayName ?: "Unknown"
            )

            // Send to all remaining members
            updatedGroup.memberIds.forEach { memberId ->
                wsManager.sendToUser(memberId, groupUpdated)
            }

            // Also notify the removed user
            wsManager.sendToUser(userId, GroupUpdated(
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
    private val wsManager: WebSocketConnectionManager,
    private val dmRepository: DMRepository,  // Added for DM security
    private val groupRepository: GroupRepository  // Added for group security
) {
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

        // SECURITY FIX: Send only to intended recipients
        if (groupId != null) {
            // Group message - send only to group members
            sendToGroupMembers(groupId, notification, senderId)
        } else if (dmId != null) {
            // DM message - send only to the 2 participants
            sendToDMParticipants(dmId, notification, senderId)
        } else {
            // Should not happen, but log it
            println("ERROR: Message has neither groupId nor dmId")
        }

        return message
    }

    private suspend fun sendToGroupMembers(groupId: String, notification: NewMessageNotification, senderId: String) {
        val group = groupRepository.findById(groupId)
        if (group == null) {
            println("Warning: Group $groupId not found when sending message")
            return
        }

        // Get all recipients (members + admin) INCLUDING SENDER
        val recipients = (group.memberIds + group.adminId).distinct()

        println("Sending group message to ${recipients.size} recipients: $recipients")

        // Send to ALL recipients INCLUDING sender (so sender sees their own message)
        recipients.forEach { recipientId ->
            wsManager.sendToUser(recipientId, notification)
        }
    }

    private suspend fun sendToDMParticipants(dmId: String, notification: NewMessageNotification, senderId: String) {
        val dm = dmRepository.findById(dmId)
        if (dm == null) {
            println("Warning: DM $dmId not found when sending message")
            return
        }

        // Send to BOTH participants (including sender)
        println("Sending DM message to both participants")

        // Send to participant 1
        wsManager.sendToUser(dm.participant1Id, notification)
        // Send to participant 2
        wsManager.sendToUser(dm.participant2Id, notification)
    }

    suspend fun getGroupMessages(groupId: String, limit: Int = 50, offset: Int = 0): PaginatedMessages {
        val messages = messageRepository.findByGroupId(groupId, limit + 1, offset)
        val hasMore = messages.size > limit
        val messageResponses = messages.take(limit).map { msg ->
            val sender = userRepository.findById(msg.senderId)
            toResponse(msg, sender?.displayName ?: "Unknown")
        }
        return PaginatedMessages(messageResponses, hasMore)
    }

    suspend fun getDMMessages(dmId: String, limit: Int = 50, offset: Int = 0): PaginatedMessages {
        val messages = messageRepository.findByDmId(dmId, limit + 1, offset)
        val hasMore = messages.size > limit
        val messageResponses = messages.take(limit).map { msg ->
            val sender = userRepository.findById(msg.senderId)
            toResponse(msg, sender?.displayName ?: "Unknown")
        }
        return PaginatedMessages(messageResponses, hasMore)
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