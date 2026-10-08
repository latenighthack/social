package com.latenighthack.social.messages.domain

import com.latenighthack.ktstore.*
import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.social.messages.v1.MessageId
import com.latenighthack.social.messages.v1.PendingMessage
import com.latenighthack.social.messages.v1.fromByteArray
import com.latenighthack.social.messages.v1.toByteArray

/**
 * Device-local durable outbox of composed messages awaiting delivery, kept outside the room so it
 * survives restarts and drives the global send-retry loop. Keyed by (room id, message id): message
 * ids are only unique within a room, so both are needed to identify a row and to look one up without
 * scanning the whole outbox.
 */
internal class PendingMessageStore(database: Database) : Store<PendingMessage>(database, PendingMessageStoreDefinitionV1) {
    private val roomIdKey = PendingMessageStoreDefinitionV1.roomIdKey
    private val messageIdKey = PendingMessageStoreDefinitionV1.messageIdKey
    private val roomIdMessageIdKey = PendingMessageStoreDefinitionV1.roomIdMessageIdKey

    suspend fun getAllPending(): List<PendingMessage> = getAll()

    suspend fun savePending(pending: PendingMessage) = save(pending)

    suspend fun deletePending(roomId: RoomId, messageId: MessageId) = delete(
        roomIdMessageIdKey.eq(
            listOf(
                BoundStoreKey.SerializedKey(roomIdKey.name.value, roomId.rawValue),
                BoundStoreKey.SerializedKey(messageIdKey.name.value, messageId.toByteArray()),
            ),
        ),
    )
}
