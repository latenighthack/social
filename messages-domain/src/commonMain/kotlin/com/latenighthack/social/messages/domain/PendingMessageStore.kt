package com.latenighthack.social.messages.domain

import com.latenighthack.ktstore.Store
import com.latenighthack.ktstore.StoreDelegate
import com.latenighthack.social.messages.v1.MessageId
import com.latenighthack.social.messages.v1.PendingMessage
import com.latenighthack.social.messages.v1.fromByteArray
import com.latenighthack.social.messages.v1.toByteArray

/**
 * Device-local durable outbox of composed messages awaiting delivery, kept outside the room so it
 * survives restarts and drives the global send-retry loop. Keyed by message id, so re-enqueuing the
 * same message overwrites rather than duplicates.
 */
internal class PendingMessageStore(delegate: StoreDelegate) : Store<PendingMessage>(
    delegate,
    "pending_messages",
    PendingMessage::toByteArray,
    PendingMessage.Companion::fromByteArray,
) {
    private val messageIdKey = serializedIndex(PendingMessage::messageId, MessageId::toByteArray)
        .also { primaryKey(it) }

    suspend fun getAllPending(): List<PendingMessage> = getAll()

    suspend fun savePending(pending: PendingMessage) = save(pending)

    suspend fun deletePending(messageId: MessageId) = delete(messageIdKey.eq(messageId.toByteArray()))
}
