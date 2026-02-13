package model

import kotlin.time.Instant
import kotlinx.serialization.Serializable

enum class UserStatus {
    ONLINE, AWAY, OFFLINE
}

@Serializable
data class User(
    val id: String,
    val username: String,
    var displayName: String,
    var avatarUrl: String? = null,
    var status: UserStatus = UserStatus.OFFLINE,
    val passwordHash: String,
    val createdAt: Instant
)

@Serializable
data class Group(
    val id: String,
    var name: String,
    var description: String? = null,
    val adminId: String,
    val memberIds: MutableList<String>,
    val createdAt: Instant
)

@Serializable
data class Message(
    val id: String,
    val senderId: String,
    val groupId: String? = null,
    val dmId: String? = null,
    val content: String,
    val createdAt: Instant,
    var editedAt: Instant? = null
)

@Serializable
data class DirectMessageConversation(
    val id: String,
    val participant1Id: String,
    val participant2Id: String,
    val createdAt: Instant
)

enum class CallType {
    DM, GROUP
}

enum class CallStatus {
    ACTIVE, ENDED
}

@Serializable
data class Call(
    val id: String,
    val roomName: String,
    val callType: CallType,
    val dmId: String? = null,
    val groupId: String? = null,
    val initiatedBy: String,
    val status: CallStatus = CallStatus.ACTIVE,
    val createdAt: Instant,
    val endedAt: Instant? = null
)

@Serializable
data class CallParticipant(
    val callId: String,
    val userId: String,
    val joinedAt: Instant,
    val leftAt: Instant? = null
)
