package com.latenighthack.social.messages.domain
import com.latenighthack.ktstore.*
import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.social.messages.v1.LocalDraft
import com.latenighthack.social.messages.v1.fromByteArray
import com.latenighthack.social.messages.v1.toByteArray

private val LocalDraft.ownerKeyStorage: ByteArray get() = ownerAccountId.encodeToByteArray()

object DraftStoreDefinitionV2 : StoreDefinition<LocalDraft>(
    StoreName("drafts"), "LocalDraft-protobuf-v1", LocalDraft.Companion::fromByteArray, LocalDraft::toByteArray,
) {
    val ownerKey = bytesIndex(IndexName("ownerAccountIdUtf8"), LocalDraft::ownerKeyStorage, "utf8-v1")
    val roomIdKey = bytesIndex(IndexName("roomId"), LocalDraft::roomId, "raw-bytes-v1")
    val ownerRoomKey = compositeIndex(IndexName("ownerRoomKey"), ownerKey, roomIdKey).also { primaryKey(it) }
}
