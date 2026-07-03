package com.latenighthack.social.messages.domain

import com.latenighthack.ktstore.Store
import com.latenighthack.ktstore.StoreDelegate
import com.latenighthack.social.messages.v1.LocalMessage
import com.latenighthack.social.messages.v1.MessageId
import com.latenighthack.social.messages.v1.fromByteArray
import com.latenighthack.social.messages.v1.toByteArray

/**
 * Device-local persistent cache of observed messages, wrapped as [LocalMessage] (room id, message
 * id, signed envelope). Keyed by the message id's raw bytes (globally unique per message).
 */
internal class MessageStore(delegate: StoreDelegate) : Store<LocalMessage>(
    delegate,
    "messages",
    LocalMessage::toByteArray,
    LocalMessage.Companion::fromByteArray,
) {
    private val messageIdKey = serializedIndex(LocalMessage::messageId, MessageId::toByteArray)
        .also { primaryKey(it) }

    suspend fun getAllMessages(): List<LocalMessage> = getAll()

    suspend fun saveMessage(message: LocalMessage) = save(message)
}
