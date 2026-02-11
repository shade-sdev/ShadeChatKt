package dto

import model.UserStatus
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

// Auth DTOs
@Serializable
data class RegisterRequest(
    val username: String,
    val password: String,
    val displayName: String
)

@Serializable
data class LoginRequest(
    val username: String,
    val password: String
)

@Serializable
data class AuthResponse(
    val token: String,
    val user: UserResponse
)

// User DTOs
@Serializable
data class UserResponse(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String?,
    val status: UserStatus,
    val createdAt: Instant
)

@Serializable
data class UpdateUserRequest(
    val displayName: String? = null,
    val avatarUrl: String? = null
)

// Group DTOs
@Serializable
data class CreateGroupRequest(
    val name: String,
    val description: String? = null,
    val memberIds: List<String>
)

@Serializable
data class GroupResponse(
    val id: String,
    val name: String,
    val description: String?,
    val adminId: String,
    val memberIds: List<String>,
    val createdAt: Instant
)

@Serializable
data class AddMemberRequest(
    val userId: String
)

// Add to DTOs.kt
@Serializable
data class GroupInvitation(
    val groupId: String,
    val groupName: String,
    val invitedBy: String,
    val invitedByDisplayName: String
)

@Serializable
data class GroupUpdated(
    val groupId: String,
    val action: String, // "member_added", "member_removed", "group_created"
    val userId: String? = null,
    val userName: String? = null
)

// Message DTOs
@Serializable
data class SendMessageRequest(
    val content: String
)

@Serializable
data class EditMessageRequest(
    val content: String
)

@Serializable
data class MessageResponse(
    val id: String,
    val senderId: String,
    val senderName: String,
    val groupId: String?,
    val dmId: String?,
    val content: String,
    val createdAt: Instant,
    val editedAt: Instant?
)

@Serializable
data class PaginatedMessages(
    val messages: List<MessageResponse>,
    val hasMore: Boolean
)

// DM DTOs
@Serializable
data class CreateDMRequest(
    val recipientId: String
)

@Serializable
data class DMConversationResponse(
    val id: String,
    val participant1Id: String,
    val participant2Id: String,
    val otherUser: UserResponse,
    val createdAt: Instant
)

// WebSocket message DTOs
@Serializable
data class WSMessage(
    val type: String,
    val data: String
)

@Serializable
data class TypingIndicator(
    val conversationId: String,
    val conversationType: String, // "dm" or "group"
    val userId: String,
    val userName: String,
    val isTyping: Boolean
)

@Serializable
data class NewMessageNotification(
    val message: MessageResponse
)

@Serializable
data class UserStatusUpdate(
    val userId: String,
    val status: UserStatus
)

@Serializable
data class SimpleMessage(
    val message: String
)