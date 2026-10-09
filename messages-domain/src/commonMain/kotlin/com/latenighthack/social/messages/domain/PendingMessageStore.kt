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
internal class PendingMessageStore(private val handle: Database) : Store<PendingMessage>(handle, PendingMessageStoreDefinitionV2) {
    private val roomIdKey = PendingMessageStoreDefinitionV2.roomIdKey
    private val messageIdKey = PendingMessageStoreDefinitionV2.messageIdKey
    private val roomIdMessageIdKey = PendingMessageStoreDefinitionV2.ownerRoomMessageKey

    fun pages() = com.latenighthack.social.runtime.storePages(handle, PendingMessageStoreDefinitionV2, roomIdKey, pageSize = 16)

    suspend fun getAllPending(): List<PendingMessage> = getAll()

    suspend fun savePending(pending: PendingMessage) = save(pending)

    suspend fun deletePending(roomId: RoomId, messageId: MessageId, owner: String = "") = delete(
        roomIdMessageIdKey.eq(
            listOf(
                BoundStoreKey.SerializedKey("ownerAccountIdUtf8", owner.encodeToByteArray()),
                BoundStoreKey.SerializedKey(roomIdKey.name.value, roomId.rawValue),
                BoundStoreKey.SerializedKey(messageIdKey.name.value, messageId.toByteArray()),
            ),
        ),
    )
}
