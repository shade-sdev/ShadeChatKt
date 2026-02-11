package repository

import model.Group
import model.Message
import model.User
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import model.DirectMessageConversation
import java.util.concurrent.ConcurrentHashMap

class UserRepository {
    private val users = ConcurrentHashMap<String, User>()
    private val usersByUsername = ConcurrentHashMap<String, User>()

    suspend fun save(user: User): User {
        users[user.id] = user
        usersByUsername[user.username] = user
        return user
    }

    suspend fun findById(id: String): User? = users[id]

    suspend fun findByUsername(username: String): User? = usersByUsername[username]

    suspend fun findAll(): List<User> = users.values.toList()

    suspend fun update(id: String, updateFn: (User) -> User): User? {
        val user = users[id] ?: return null
        val updated = updateFn(user)
        users[id] = updated
        usersByUsername[updated.username] = updated
        return updated
    }
}

class GroupRepository {
    private val groups = ConcurrentHashMap<String, Group>()

    suspend fun save(group: Group): Group {
        groups[group.id] = group
        return group
    }

    suspend fun findById(id: String): Group? = groups[id]

    suspend fun findByUserId(userId: String): List<Group> {
        return groups.values.filter { userId in it.memberIds }
    }

    suspend fun update(id: String, updateFn: (Group) -> Group): Group? {
        val group = groups[id] ?: return null
        val updated = updateFn(group)
        groups[id] = updated
        return updated
    }

    suspend fun delete(id: String): Boolean {
        return groups.remove(id) != null
    }
}

class MessageRepository {
    private val messages = ConcurrentHashMap<String, Message>()
    private val groupMessages = ConcurrentHashMap<String, MutableList<String>>()
    private val dmMessages = ConcurrentHashMap<String, MutableList<String>>()
    private val mutex = Mutex()

    suspend fun save(message: Message): Message {
        messages[message.id] = message

        mutex.withLock {
            when {
                message.groupId != null -> {
                    groupMessages.getOrPut(message.groupId) { mutableListOf() }.add(message.id)
                }
                message.dmId != null -> {
                    dmMessages.getOrPut(message.dmId) { mutableListOf() }.add(message.id)
                }
            }
        }

        return message
    }

    suspend fun findById(id: String): Message? = messages[id]

    suspend fun findByGroupId(groupId: String, limit: Int = 50, offset: Int = 0): List<Message> {
        val messageIds = groupMessages[groupId] ?: return emptyList()
        return messageIds.reversed()
            .drop(offset)
            .take(limit)
            .mapNotNull { messages[it] }
    }

    suspend fun findByDmId(dmId: String, limit: Int = 50, offset: Int = 0): List<Message> {
        val messageIds = dmMessages[dmId] ?: return emptyList()
        return messageIds.reversed()
            .drop(offset)
            .take(limit)
            .mapNotNull { messages[it] }
    }

    suspend fun update(id: String, updateFn: (Message) -> Message): Message? {
        val message = messages[id] ?: return null
        val updated = updateFn(message)
        messages[id] = updated
        return updated
    }

    suspend fun delete(id: String): Boolean {
        val message = messages.remove(id) ?: return false

        mutex.withLock {
            message.groupId?.let { groupMessages[it]?.remove(id) }
            message.dmId?.let { dmMessages[it]?.remove(id) }
        }

        return true
    }
}

class DMRepository {
    private val conversations = ConcurrentHashMap<String, DirectMessageConversation>()
    private val userConversations = ConcurrentHashMap<String, MutableSet<String>>()

    suspend fun save(dm: DirectMessageConversation): DirectMessageConversation {
        conversations[dm.id] = dm
        userConversations.getOrPut(dm.participant1Id) { mutableSetOf() }.add(dm.id)
        userConversations.getOrPut(dm.participant2Id) { mutableSetOf() }.add(dm.id)
        return dm
    }

    suspend fun findById(id: String): DirectMessageConversation? = conversations[id]

    suspend fun findByParticipants(user1Id: String, user2Id: String): DirectMessageConversation? {
        return conversations.values.find {
            (it.participant1Id == user1Id && it.participant2Id == user2Id) ||
                    (it.participant1Id == user2Id && it.participant2Id == user1Id)
        }
    }

    suspend fun findByUserId(userId: String): List<DirectMessageConversation> {
        val conversationIds = userConversations[userId] ?: return emptyList()
        return conversationIds.mapNotNull { conversations[it] }
    }
}