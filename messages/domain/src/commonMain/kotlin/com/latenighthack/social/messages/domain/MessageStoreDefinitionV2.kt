package com.latenighthack.social.messages.domain
import com.latenighthack.ktstore.*
import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.social.messages.v1.LocalMessage
import com.latenighthack.social.messages.v1.MessageId
import com.latenighthack.social.messages.v1.fromByteArray
import com.latenighthack.social.messages.v1.toByteArray

private val LocalMessage.messageIdKeyStorage: ByteArray get() = requireNotNull(messageId).toByteArray()

private val LocalMessage.ownerKeyStorage: ByteArray get() = ownerAccountId.encodeToByteArray()

object MessageStoreDefinitionV2 : StoreDefinition<LocalMessage>(
    StoreName("messages"), "LocalMessage-protobuf-v1", LocalMessage.Companion::fromByteArray, LocalMessage::toByteArray,
) {
    val ownerKey = bytesIndex(IndexName("ownerAccountIdUtf8"), LocalMessage::ownerKeyStorage, "utf8-v1")
    val roomIdKey = bytesIndex(IndexName("roomId"), LocalMessage::roomId, "raw-bytes-v1")
    val messageIdKey = bytesIndex(IndexName("messageIdtoByteArray"), LocalMessage::messageIdKeyStorage, "toByteArray-v1")
    val roomIdMessageIdKey = compositeIndex(IndexName("composite_roomId_messageIdtoByteArray"), roomIdKey, messageIdKey)
    val ownerRoomMessageKey = compositeIndex(IndexName("ownerRoomMessageKey"), ownerKey, roomIdKey, messageIdKey).also { primaryKey(it) }
}
