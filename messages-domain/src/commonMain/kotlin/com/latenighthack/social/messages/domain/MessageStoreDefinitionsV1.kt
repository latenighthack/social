package com.latenighthack.social.messages.domain
import com.latenighthack.ktstore.*
import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.social.messages.v1.LocalMessage
import com.latenighthack.social.messages.v1.MessageId
import com.latenighthack.social.messages.v1.fromByteArray
import com.latenighthack.social.messages.v1.toByteArray

private val LocalMessage.messageIdKeyStorage: ByteArray get() = requireNotNull(messageId).toByteArray()

object MessageStoreDefinitionV1 : StoreDefinition<LocalMessage>(
    StoreName("messages"), "LocalMessage-protobuf-v1", LocalMessage.Companion::fromByteArray, LocalMessage::toByteArray,
) {
    val roomIdKey = bytesIndex(IndexName("roomId"), LocalMessage::roomId, "raw-bytes-v1")
    val messageIdKey = bytesIndex(IndexName("messageIdtoByteArray"), LocalMessage::messageIdKeyStorage, "toByteArray-v1")
    val roomIdMessageIdKey = compositeIndex(IndexName("composite_roomId_messageIdtoByteArray"), roomIdKey, messageIdKey).also { primaryKey(it) }
}
