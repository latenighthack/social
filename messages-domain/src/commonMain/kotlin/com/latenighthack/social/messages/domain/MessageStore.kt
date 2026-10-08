package com.latenighthack.social.messages.domain

import com.latenighthack.ktstore.*
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
internal class MessageStore(private val handle: Database) : Store<LocalMessage>(handle, MessageStoreDefinitionV1) {
    private val roomIdKey = MessageStoreDefinitionV1.roomIdKey
    private val messageIdKey = MessageStoreDefinitionV1.messageIdKey
    private val roomIdMessageIdKey = MessageStoreDefinitionV1.roomIdMessageIdKey

    suspend fun getMessagesForRoom(roomId: RoomId): List<LocalMessage> = getAll(roomIdKey.eq(roomId.rawValue))

    suspend fun getRecentMessages(roomId: RoomId, owned: (LocalMessage) -> Boolean, limit: Int = 1000,
        before: MessageEntry? = null): List<LocalMessage> {
        var selected = emptyList<Pair<LocalMessage, MessageEntry>>()
        com.latenighthack.social.runtime.storePages(handle, MessageStoreDefinitionV1, roomIdKey,
            roomId.rawValue, roomId.rawValue).collect { page ->
            val rows = page.filter(owned).mapNotNull { local -> local.message?.let { signed ->
                val payload = com.latenighthack.social.messages.v1.MessagePayload.fromByteArray(signed.content)
                local to MessageEntry(payload, local.status)
            } }.filter { before == null || messageOrder.compare(it.second, before) < 0 }
            selected = (selected + rows).sortedWith { a, b -> messageOrder.compare(a.second, b.second) }.takeLast(limit)
        }
        return selected.map { it.first }
    }

    /** Dedup lookup for a room that isn't loaded in memory; loaded rooms check their in-memory id set. */
    suspend fun getMessage(roomId: RoomId, messageId: MessageId): LocalMessage? = get(
        roomIdMessageIdKey.eq(
            listOf(
                BoundStoreKey.SerializedKey(roomIdKey.name.value, roomId.rawValue),
                BoundStoreKey.SerializedKey(messageIdKey.name.value, messageId.toByteArray()),
            ),
        ),
    )

    suspend fun getAllMessages(): List<LocalMessage> = getAll()
    suspend fun deleteMessage(roomId: RoomId, messageId: MessageId) = delete(roomIdMessageIdKey.eq(listOf(
        BoundStoreKey.SerializedKey(roomIdKey.name.value, roomId.rawValue),
        BoundStoreKey.SerializedKey(messageIdKey.name.value, messageId.toByteArray()),
    )))

    suspend fun saveMessage(message: LocalMessage) = save(message)
}
