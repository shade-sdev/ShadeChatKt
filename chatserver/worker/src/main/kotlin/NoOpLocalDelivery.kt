import dto.WSMessage
import redis.LocalMessageDelivery

class NoOpLocalDelivery : LocalMessageDelivery {
    override suspend fun deliverToUser(userId: String, message: WSMessage) {
        // Worker has no local connections - messages go through Redis Pub/Sub only
    }

    override suspend fun deliverToAll(message: WSMessage) {
        // Worker has no local connections
    }

    override suspend fun deliverToGroup(groupId: String, message: WSMessage) {
        // Worker has no local connections
    }
}