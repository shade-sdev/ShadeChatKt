package jobs

import kotlinx.serialization.Serializable

/**
 * Background jobs processed by workers
 */

@Serializable
sealed class Job {
    abstract val id: String
    abstract val createdAt: Long
}

@Serializable
data class CreateGroupJob(
    override val id: String,
    override val createdAt: Long,
    val name: String,
    val description: String?,
    val adminId: String,
    val memberIds: List<String>
) : Job()

@Serializable
data class AddMemberJob(
    override val id: String,
    override val createdAt: Long,
    val groupId: String,
    val userId: String
) : Job()

@Serializable
data class RemoveMemberJob(
    override val id: String,
    override val createdAt: Long,
    val groupId: String,
    val userId: String
) : Job()