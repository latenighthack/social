package com.latenighthack.social.messages.domain

import com.latenighthack.ktstore.BoundStoreKey
import com.latenighthack.ktstore.Store
import com.latenighthack.ktstore.StoreDelegate
import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.social.messages.v1.LocalMessage
import com.latenighthack.social.messages.v1.MessageId
import com.latenighthack.social.messages.v1.fromByteArray
import com.latenighthack.social.messages.v1.toByteArray

/**
 * Device-local persistent cache of received, sent, and not-yet-sent messages, wrapped as
 * [LocalMessage] (room id, message id, signed envelope, delivery status). Keyed by (room id, message
 * id) — message ids are only unique within a room — so re-saving the same message overwrites rather
 * than duplicates, which is what deduplicates our own echoed sends and replays on receive. Messages
 * are loaded a room at a time via [getMessagesForRoom]; the manager never loads every room's history
 * up front.
 */
internal class MessageStore(delegate: StoreDelegate) : Store<LocalMessage>(
    delegate,
    "messages",
    LocalMessage::toByteArray,
    LocalMessage.Companion::fromByteArray,
) {
    private val roomIdKey = bytesIndex(LocalMessage::roomId)
    private val messageIdKey = serializedIndex(LocalMessage::messageId, MessageId::toByteArray)
    private val roomIdMessageIdKey = compositeIndex(roomIdKey, messageIdKey).also { primaryKey(it) }

    suspend fun getMessagesForRoom(roomId: RoomId): List<LocalMessage> = getAll(roomIdKey.eq(roomId.rawValue))

    /** Dedup lookup for a room that isn't loaded in memory; loaded rooms check their in-memory id set. */
    suspend fun getMessage(roomId: RoomId, messageId: MessageId): LocalMessage? = get(
        roomIdMessageIdKey.eq(
            listOf(
                BoundStoreKey.SerializedKey(roomIdKey.name, roomId.rawValue),
                BoundStoreKey.SerializedKey(messageIdKey.name, messageId.toByteArray()),
            ),
        ),
    )

    suspend fun saveMessage(message: LocalMessage) = save(message)
}
