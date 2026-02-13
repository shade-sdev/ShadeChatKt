package repository

import database.dbQuery
import model.Call
import model.CallParticipant
import model.CallStatus
import model.CallType
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import tables.CallParticipantsTable
import tables.CallsTable
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.*
import kotlin.time.Instant
import kotlin.time.toJavaInstant

class CallRepository {

    suspend fun save(call: Call): Call = dbQuery {
        CallsTable.insert {
            it[id] = UUID.fromString(call.id)
            it[roomName] = call.roomName
            it[callType] = call.callType.name
            it[dmId] = call.dmId?.let(UUID::fromString)
            it[groupId] = call.groupId?.let(UUID::fromString)
            it[initiatedBy] = UUID.fromString(call.initiatedBy)
            it[status] = call.status.name
            it[createdAt] = OffsetDateTime.ofInstant(call.createdAt.toJavaInstant(), ZoneOffset.UTC)
            it[endedAt] = call.endedAt?.let { instant ->
                OffsetDateTime.ofInstant(instant.toJavaInstant(), ZoneOffset.UTC)
            }
        }
        call
    }

    suspend fun findById(id: String): Call? = dbQuery {
        CallsTable.selectAll()
            .where { CallsTable.id eq UUID.fromString(id) }
            .limit(1)
            .map(::toCall)
            .singleOrNull()
    }

    suspend fun findByRoomName(roomName: String): Call? = dbQuery {
        CallsTable.selectAll()
            .where { CallsTable.roomName eq roomName }
            .limit(1)
            .map(::toCall)
            .singleOrNull()
    }

    suspend fun findActiveCallByDmId(dmId: String): Call? = dbQuery {
        CallsTable.selectAll()
            .where {
                (CallsTable.dmId eq UUID.fromString(dmId)) and
                        (CallsTable.status eq CallStatus.ACTIVE.name)
            }
            .limit(1)
            .map(::toCall)
            .singleOrNull()
    }

    suspend fun findActiveCallByGroupId(groupId: String): Call? = dbQuery {
        CallsTable.selectAll()
            .where {
                (CallsTable.groupId eq UUID.fromString(groupId)) and
                        (CallsTable.status eq CallStatus.ACTIVE.name)
            }
            .limit(1)
            .map(::toCall)
            .singleOrNull()
    }

    suspend fun update(id: String, updateFn: (Call) -> Call): Call? = dbQuery {
        val existing = findById(id) ?: return@dbQuery null
        val updated = updateFn(existing)

        CallsTable.update({ CallsTable.id eq UUID.fromString(id) }) {
            it[status] = updated.status.name
            it[endedAt] = updated.endedAt?.let { instant ->
                OffsetDateTime.ofInstant(instant.toJavaInstant(), ZoneOffset.UTC)
            }
        }

        updated
    }

    suspend fun delete(id: String): Boolean = dbQuery {
        CallsTable.deleteWhere { CallsTable.id eq UUID.fromString(id) } > 0
    }

    // Participant methods
    suspend fun addParticipant(participant: CallParticipant): CallParticipant = dbQuery {
        CallParticipantsTable.insert {
            it[callId] = UUID.fromString(participant.callId)
            it[userId] = UUID.fromString(participant.userId)
            it[joinedAt] = OffsetDateTime.ofInstant(participant.joinedAt.toJavaInstant(), ZoneOffset.UTC)
            it[leftAt] = participant.leftAt?.let { instant ->
                OffsetDateTime.ofInstant(instant.toJavaInstant(), ZoneOffset.UTC)
            }
        }
        participant
    }

    suspend fun findParticipantsByCallId(callId: String): List<CallParticipant> = dbQuery {
        CallParticipantsTable.selectAll()
            .where { CallParticipantsTable.callId eq UUID.fromString(callId) }
            .map(::toCallParticipant)
    }

    suspend fun updateParticipantLeftTime(callId: String, userId: String, leftAt: Instant): Boolean = dbQuery {
        CallParticipantsTable.update({
            (CallParticipantsTable.callId eq UUID.fromString(callId)) and
                    (CallParticipantsTable.userId eq UUID.fromString(userId))
        }) {
            it[CallParticipantsTable.leftAt] = OffsetDateTime.ofInstant(leftAt.toJavaInstant(), ZoneOffset.UTC)
        } > 0
    }

    suspend fun findActiveParticipants(callId: String): List<CallParticipant> = dbQuery {
        CallParticipantsTable.selectAll()
            .where {
                (CallParticipantsTable.callId eq UUID.fromString(callId)) and
                        (CallParticipantsTable.leftAt.isNull())
            }
            .map(::toCallParticipant)
    }

    private fun toCall(row: ResultRow): Call {
        return Call(
            id = row[CallsTable.id].toString(),
            roomName = row[CallsTable.roomName],
            callType = CallType.valueOf(row[CallsTable.callType]),
            dmId = row[CallsTable.dmId]?.toString(),
            groupId = row[CallsTable.groupId]?.toString(),
            initiatedBy = row[CallsTable.initiatedBy].toString(),
            status = CallStatus.valueOf(row[CallsTable.status]),
            createdAt = Instant.fromEpochMilliseconds(row[CallsTable.createdAt].toEpochSecond() * 1000),
            endedAt = row[CallsTable.endedAt]?.let {
                Instant.fromEpochMilliseconds(it.toEpochSecond() * 1000)
            }
        )
    }

    private fun toCallParticipant(row: ResultRow): CallParticipant {
        return CallParticipant(
            callId = row[CallParticipantsTable.callId].toString(),
            userId = row[CallParticipantsTable.userId].toString(),
            joinedAt = Instant.fromEpochMilliseconds(row[CallParticipantsTable.joinedAt].toEpochSecond() * 1000),
            leftAt = row[CallParticipantsTable.leftAt]?.let {
                Instant.fromEpochMilliseconds(it.toEpochSecond() * 1000)
            }
        )
    }
}