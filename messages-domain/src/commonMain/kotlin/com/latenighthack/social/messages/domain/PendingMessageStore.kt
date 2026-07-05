package com.latenighthack.social.messages.domain

import com.latenighthack.ktstore.BoundStoreKey
import com.latenighthack.ktstore.Store
import com.latenighthack.ktstore.StoreDelegate
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
internal class PendingMessageStore(delegate: StoreDelegate) : Store<PendingMessage>(
    delegate,
    "pending_messages",
    PendingMessage::toByteArray,
    PendingMessage.Companion::fromByteArray,
) {
    private val roomIdKey = bytesIndex(PendingMessage::roomId)
    private val messageIdKey = serializedIndex(PendingMessage::messageId, MessageId::toByteArray)
    private val roomIdMessageIdKey = compositeIndex(roomIdKey, messageIdKey).also { primaryKey(it) }

    suspend fun getAllPending(): List<PendingMessage> = getAll()

    suspend fun savePending(pending: PendingMessage) = save(pending)

    suspend fun deletePending(roomId: RoomId, messageId: MessageId) = delete(
        roomIdMessageIdKey.eq(
            listOf(
                BoundStoreKey.SerializedKey(roomIdKey.name, roomId.rawValue),
                BoundStoreKey.SerializedKey(messageIdKey.name, messageId.toByteArray()),
            ),
        ),
    )
}
