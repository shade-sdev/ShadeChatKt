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
import io.livekit.server.AccessToken
import io.livekit.server.RoomServiceClient
import livekit.LivekitModels
import livekit.LivekitRoom
import model.Call
import model.CallParticipant
import model.CallStatus
import model.CallType
import repository.CallRepository
import util.log
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
        log().info {"Updating status for user $userId to $status"}
        userRepository.update(userId) { it.copy(status = status) }

        val statusUpdate = UserStatusUpdate(userId, status)
        log().info {"Broadcasting status update via Redis"}

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

/**
 * Call service - orchestrates voice/video call logic
 * Integrates LiveKit with existing chat infrastructure
 */
class CallService(
    private val callRepository: CallRepository,
    private val userRepository: UserRepository,
    private val dmRepository: DMRepository,
    private val groupRepository: GroupRepository,
    private val liveKitService: LiveKitService,
    private val messageBus: RedisMessageBus
) {

    /**
     * Initiate a new call (DM or Group)
     * Creates LiveKit room and sends invitations via WebSocket
     */
    suspend fun initiateCall(
        userId: String,
        conversationId: String,
        callType: CallType
    ): CallResponse {
        log().info { "User $userId initiating $callType call for conversation $conversationId" }

        // Validate conversation exists and user has access
        val participants = when (callType) {
            CallType.DM -> {
                val dm = dmRepository.findById(conversationId)
                    ?: throw IllegalArgumentException("DM conversation not found")

                if (userId != dm.participant1Id && userId != dm.participant2Id) {
                    throw IllegalArgumentException("User not a participant in this DM")
                }

                // Check for existing active call
                val existingCall = callRepository.findActiveCallByDmId(conversationId)
                if (existingCall != null) {
                    throw IllegalStateException("Call already in progress for this conversation")
                }

                listOf(dm.participant1Id, dm.participant2Id)
            }

            CallType.GROUP -> {
                val group = groupRepository.findById(conversationId)
                    ?: throw IllegalArgumentException("Group not found")

                if (userId !in group.memberIds) {
                    throw IllegalArgumentException("User not a member of this group")
                }

                // Check for existing active call
                val existingCall = callRepository.findActiveCallByGroupId(conversationId)
                if (existingCall != null) {
                    throw IllegalStateException("Call already in progress for this group")
                }

                (group.memberIds + group.adminId).distinct()
            }
        }

        // Generate unique room name
        val roomName = "call_${UUID.randomUUID()}"

        // Create LiveKit room
        liveKitService.createRoom(roomName, maxParticipants = if (callType == CallType.DM) 2 else null)

        // Create call record
        val call = Call(
            id = UUID.randomUUID().toString(),
            roomName = roomName,
            callType = callType,
            dmId = if (callType == CallType.DM) conversationId else null,
            groupId = if (callType == CallType.GROUP) conversationId else null,
            initiatedBy = userId,
            status = CallStatus.ACTIVE,
            createdAt = Clock.System.now()
        )

        callRepository.save(call)

        // Add initiator as first participant
        val initiatorParticipant = CallParticipant(
            callId = call.id,
            userId = userId,
            joinedAt = Clock.System.now()
        )
        callRepository.addParticipant(initiatorParticipant)

        // Send invitations to all participants via WebSocket
        val initiator = userRepository.findById(userId)!!
        val invitation = CallInvitation(
            callId = call.id,
            roomName = roomName,
            callType = callType.name.lowercase(),
            conversationId = conversationId,
            initiatedBy = userId,
            initiatorName = initiator.displayName,
            participants = participants
        )

        // Send to all participants except initiator
        participants.filter { it != userId }.forEach { participantId ->
            messageBus.publishToUser(participantId, invitation)
        }

        log().info { "Call ${call.id} initiated successfully" }

        return toResponse(call)
    }

    /**
     * Join an existing call
     * Generates LiveKit token for the user
     */
    suspend fun joinCall(userId: String, callId: String): CallTokenResponse {
        log().info { "User $userId joining call $callId" }

        val call = callRepository.findById(callId)
            ?: throw IllegalArgumentException("Call not found")

        if (call.status != CallStatus.ACTIVE) {
            throw IllegalStateException("Call has ended")
        }

        // Verify user is invited to this call
        val isAuthorized = when (call.callType) {
            CallType.DM -> {
                val dm = dmRepository.findById(call.dmId!!)!!
                userId == dm.participant1Id || userId == dm.participant2Id
            }
            CallType.GROUP -> {
                val group = groupRepository.findById(call.groupId!!)!!
                userId in group.memberIds || userId == group.adminId
            }
        }

        if (!isAuthorized) {
            throw IllegalArgumentException("User not authorized to join this call")
        }

        // Check if user already in call
        val existingParticipant = callRepository.findParticipantsByCallId(callId)
            .find { it.userId == userId && it.leftAt == null }

        if (existingParticipant == null) {
            // Add as new participant
            val participant = CallParticipant(
                callId = callId,
                userId = userId,
                joinedAt = Clock.System.now()
            )
            callRepository.addParticipant(participant)

            // Notify other participants
            val user = userRepository.findById(userId)!!
            val statusUpdate = CallStatusUpdate(
                callId = callId,
                action = "participant_joined",
                userId = userId,
                userName = user.displayName
            )

            // Get all other active participants
            val otherParticipants = callRepository.findActiveParticipants(callId)
                .filter { it.userId != userId }

            otherParticipants.forEach { participant ->
                messageBus.publishToUser(participant.userId, statusUpdate)
            }
        }

        // Generate LiveKit token
        val user = userRepository.findById(userId)!!
        val token = liveKitService.generateToken(
            roomName = call.roomName,
            userId = userId,
            userName = user.displayName,
            canPublish = true,
            canSubscribe = true
        )

        val liveKitUrl = System.getenv("LIVEKIT_URL") ?: "ws://localhost:7880"

        return CallTokenResponse(
            callId = call.id,
            roomName = call.roomName,
            token = token,
            url = liveKitUrl
        )
    }

    /**
     * End a call
     * Only the initiator can end the call
     */
    suspend fun endCall(userId: String, callId: String): Boolean {
        log().info { "User $userId ending call $callId" }

        val call = callRepository.findById(callId)
            ?: throw IllegalArgumentException("Call not found")

        // Only initiator can end the call
        if (call.initiatedBy != userId) {
            throw IllegalArgumentException("Only the call initiator can end the call")
        }

        if (call.status == CallStatus.ENDED) {
            return true
        }

        // Update call status
        callRepository.update(callId) {
            it.copy(
                status = CallStatus.ENDED,
                endedAt = Clock.System.now()
            )
        }

        // Mark all active participants as left
        val activeParticipants = callRepository.findActiveParticipants(callId)
        val now = Clock.System.now()
        activeParticipants.forEach { participant ->
            callRepository.updateParticipantLeftTime(callId, participant.userId, now)
        }

        // Delete LiveKit room
        liveKitService.deleteRoom(call.roomName)

        // Notify all participants
        val statusUpdate = CallStatusUpdate(
            callId = callId,
            action = "ended"
        )

        activeParticipants.forEach { participant ->
            messageBus.publishToUser(participant.userId, statusUpdate)
        }

        log().info { "Call $callId ended successfully" }

        return true
    }

    /**
     * Leave a call (participant leaves but call continues)
     */
    suspend fun leaveCall(userId: String, callId: String): Boolean {
        log().info { "User $userId leaving call $callId" }

        val call = callRepository.findById(callId)
            ?: throw IllegalArgumentException("Call not found")

        if (call.status == CallStatus.ENDED) {
            return true
        }

        // Mark participant as left
        callRepository.updateParticipantLeftTime(callId, userId, Clock.System.now())

        // Notify other participants
        val user = userRepository.findById(userId)!!
        val statusUpdate = CallStatusUpdate(
            callId = callId,
            action = "participant_left",
            userId = userId,
            userName = user.displayName
        )

        val activeParticipants = callRepository.findActiveParticipants(callId)
        activeParticipants.forEach { participant ->
            messageBus.publishToUser(participant.userId, statusUpdate)
        }

        // If DM call and both left, end the call
        if (call.callType == CallType.DM) {
            val remainingParticipants = callRepository.findActiveParticipants(callId)
            if (remainingParticipants.isEmpty()) {
                log().info { "All participants left DM call $callId, ending call" }
                callRepository.update(callId) {
                    it.copy(status = CallStatus.ENDED, endedAt = Clock.System.now())
                }
                liveKitService.deleteRoom(call.roomName)
            }
        }

        return true
    }

    /**
     * Get active call for a conversation (if any)
     */
    suspend fun getActiveCall(conversationId: String, callType: CallType): CallResponse? {
        val call = when (callType) {
            CallType.DM -> callRepository.findActiveCallByDmId(conversationId)
            CallType.GROUP -> callRepository.findActiveCallByGroupId(conversationId)
        }

        return call?.let { toResponse(it) }
    }

    /**
     * Get call details
     */
    suspend fun getCall(callId: String): CallResponse? {
        val call = callRepository.findById(callId) ?: return null
        return toResponse(call)
    }

    private suspend fun toResponse(call: Call): CallResponse {
        val participants = callRepository.findParticipantsByCallId(call.id)
        val initiator = userRepository.findById(call.initiatedBy)!!

        val participantResponses = participants.map { participant ->
            val user = userRepository.findById(participant.userId)!!
            CallParticipantResponse(
                userId = user.id,
                userName = user.displayName,
                joinedAt = participant.joinedAt,
                leftAt = participant.leftAt
            )
        }

        return CallResponse(
            id = call.id,
            roomName = call.roomName,
            callType = call.callType,
            dmId = call.dmId,
            groupId = call.groupId,
            initiatedBy = call.initiatedBy,
            initiatorName = initiator.displayName,
            status = call.status,
            participants = participantResponses,
            createdAt = call.createdAt,
            endedAt = call.endedAt
        )
    }
}


/**
 * LiveKit service - handles LiveKit SDK operations
 * Wrapper around LiveKit server SDK for room and token management
 */
class LiveKitService {
    private val apiUrl: String = System.getenv("LIVEKIT_URL") ?: "http://localhost:7880"
    private val apiKey: String = System.getenv("LIVEKIT_API_KEY") ?: "devkey"
    private val apiSecret: String = System.getenv("LIVEKIT_API_SECRET") ?: "secret"

    private val roomClient: RoomServiceClient by lazy {
        RoomServiceClient.createClient(apiUrl, apiKey, apiSecret)
    }

    /**
     * Create a LiveKit room
     * @param roomName Unique room identifier
     * @param maxParticipants Optional max participants (null = unlimited)
     * @return LiveKit room object
     */
    suspend fun createRoom(roomName: String, maxParticipants: Int? = null): LivekitModels.Room {
        log().info { "Creating LiveKit room: $roomName" }

        val call = roomClient.createRoom(name = roomName, maxParticipants = maxParticipants)
        val response = call.execute()

        if (!response.isSuccessful) {
            throw Exception("Failed to create room: ${response.errorBody()?.string()}")
        }

        return response.body() ?: throw Exception("Empty response from LiveKit")
    }

    /**
     * Generate access token for a user to join a room
     * @param roomName Room to join
     * @param userId User identifier
     * @param userName Display name
     * @param canPublish Whether user can publish audio/video
     * @param canSubscribe Whether user can subscribe to others' streams
     * @return JWT token string
     */
    fun generateToken(
        roomName: String,
        userId: String,
        userName: String,
        canPublish: Boolean = true,
        canSubscribe: Boolean = true
    ): String {
        log().info { "Generating token for user $userId in room $roomName" }

        val token = AccessToken(apiKey, apiSecret)
        token.name = userName
        token.identity = userId
        token.metadata = """{"userId":"$userId"}"""

        // Set permissions
        token.addGrants(
            io.livekit.server.RoomJoin(true),
            io.livekit.server.RoomName(roomName)
        )

        if (canPublish) {
            token.addGrants(
                io.livekit.server.CanPublish(true),
                io.livekit.server.CanPublishData(true)
            )
        }

        if (canSubscribe) {
            token.addGrants(io.livekit.server.CanSubscribe(true))
        }

        // Token valid for 6 hours
        token.ttl = 6 * 60 * 60 * 1000

        return token.toJwt()
    }

    /**
     * Delete a room (cleanup after call ends)
     */
    suspend fun deleteRoom(roomName: String): Boolean {
        log().info { "Deleting LiveKit room: $roomName" }

        try {
            val call = roomClient.deleteRoom(roomName)
            val response = call.execute()
            return response.isSuccessful
        } catch (e: Exception) {
            log().error(e) { "Failed to delete room $roomName" }
            return false
        }
    }

    /**
     * List participants in a room
     */
    suspend fun listParticipants(roomName: String): List<LivekitModels.ParticipantInfo> {
        try {
            val call = roomClient.listParticipants(roomName)
            val response = call.execute()

            if (!response.isSuccessful) {
                log().warn { "Failed to list participants for room $roomName" }
                return emptyList()
            }

            return response.body() ?: emptyList()
        } catch (e: Exception) {
            log().error(e) { "Error listing participants for room $roomName" }
            return emptyList()
        }
    }

    /**
     * Get room info
     */
    suspend fun getRoom(roomName: String): LivekitModels.Room? {
        try {
            val call = roomClient.listRooms(listOf(roomName))
            val response = call.execute()

            if (!response.isSuccessful) {
                return null
            }

            return response.body()?.firstOrNull()
        } catch (e: Exception) {
            log().error(e) { "Error getting room $roomName" }
            return null
        }
    }
}