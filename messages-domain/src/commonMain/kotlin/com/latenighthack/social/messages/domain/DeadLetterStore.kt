package com.latenighthack.social.messages.domain

import com.latenighthack.ktstore.Store
import com.latenighthack.ktstore.StoreDelegate
import com.latenighthack.social.messages.v1.MessageId
import com.latenighthack.social.messages.v1.PendingMessage
import com.latenighthack.social.messages.v1.fromByteArray
import com.latenighthack.social.messages.v1.toByteArray

/**
 * Device-local durable store of sends that exhausted their outbox retries (or were permanently
 * rejected). Keyed by message id. A dead-lettered message is terminal until an explicit [retry]
 * moves it back into the [PendingMessageStore].
 */
internal class DeadLetterStore(delegate: StoreDelegate) : Store<PendingMessage>(
    delegate,
    "dead_letter_messages",
    PendingMessage::toByteArray,
    PendingMessage.Companion::fromByteArray,
) {
    private val messageIdKey = serializedIndex(PendingMessage::messageId, MessageId::toByteArray)
        .also { primaryKey(it) }

    suspend fun getAllDeadLettered(): List<PendingMessage> = getAll()

    suspend fun saveDeadLettered(dead: PendingMessage) = save(dead)

    suspend fun deleteDeadLettered(messageId: MessageId) = delete(messageIdKey.eq(messageId.toByteArray()))
}
