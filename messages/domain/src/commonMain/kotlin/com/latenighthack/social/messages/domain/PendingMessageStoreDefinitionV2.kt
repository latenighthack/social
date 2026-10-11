package com.latenighthack.social.messages.domain
import com.latenighthack.ktstore.*
import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.social.messages.v1.MessageId
import com.latenighthack.social.messages.v1.PendingMessage
import com.latenighthack.social.messages.v1.fromByteArray
import com.latenighthack.social.messages.v1.toByteArray

private val PendingMessage.messageIdKeyStorage: ByteArray get() = requireNotNull(messageId).toByteArray()

private val PendingMessage.ownerKeyStorage: ByteArray get() = ownerAccountId.encodeToByteArray()

object PendingMessageStoreDefinitionV2 : StoreDefinition<PendingMessage>(
    StoreName("pending_messages"), "PendingMessage-protobuf-v1", PendingMessage.Companion::fromByteArray, PendingMessage::toByteArray,
) {
    val ownerKey = bytesIndex(IndexName("ownerAccountIdUtf8"), PendingMessage::ownerKeyStorage, "utf8-v1")
    val roomIdKey = bytesIndex(IndexName("roomId"), PendingMessage::roomId, "raw-bytes-v1")
    val messageIdKey = bytesIndex(IndexName("messageIdtoByteArray"), PendingMessage::messageIdKeyStorage, "toByteArray-v1")
    val roomIdMessageIdKey = compositeIndex(IndexName("composite_roomId_messageIdtoByteArray"), roomIdKey, messageIdKey)
    val ownerRoomMessageKey = compositeIndex(IndexName("ownerRoomMessageKey"), ownerKey, roomIdKey, messageIdKey).also { primaryKey(it) }
}
