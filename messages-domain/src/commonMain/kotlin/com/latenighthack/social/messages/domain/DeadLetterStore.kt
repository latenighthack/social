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
 * Device-local durable store of sends that exhausted their outbox retries (or were permanently
 * rejected). Keyed by (room id, message id) — message ids are only unique within a room — so a
 * dead-lettered message can be looked up and removed directly on [retry] without scanning. A
 * dead-lettered message is terminal until an explicit retry moves it back into the outbox.
 */
internal class DeadLetterStore(delegate: StoreDelegate) : Store<PendingMessage>(
    delegate,
    "dead_letter_messages",
    PendingMessage::toByteArray,
    PendingMessage.Companion::fromByteArray,
) {
    private val roomIdKey = bytesIndex(PendingMessage::roomId)
    private val messageIdKey = serializedIndex(PendingMessage::messageId, MessageId::toByteArray)
    private val roomIdMessageIdKey = compositeIndex(roomIdKey, messageIdKey).also { primaryKey(it) }

    suspend fun saveDeadLettered(dead: PendingMessage) = save(dead)

    suspend fun getDeadLettered(roomId: RoomId, messageId: MessageId): PendingMessage? =
        get(roomIdMessageIdKey.eq(key(roomId, messageId)))

    suspend fun deleteDeadLettered(roomId: RoomId, messageId: MessageId) =
        delete(roomIdMessageIdKey.eq(key(roomId, messageId)))

    private fun key(roomId: RoomId, messageId: MessageId) = listOf(
        BoundStoreKey.SerializedKey(roomIdKey.name, roomId.rawValue),
        BoundStoreKey.SerializedKey(messageIdKey.name, messageId.toByteArray()),
    )
}
