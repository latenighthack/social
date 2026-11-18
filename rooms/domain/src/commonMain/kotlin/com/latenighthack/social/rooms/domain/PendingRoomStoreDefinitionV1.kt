package com.latenighthack.social.rooms.domain

import com.latenighthack.ktstore.*
import com.latenighthack.social.rooms.v1.LocalPendingRoom
import com.latenighthack.social.rooms.v1.fromByteArray
import com.latenighthack.social.rooms.v1.toByteArray

private val LocalPendingRoom.ownerKeyStorage: ByteArray get() = ownerAccountId.encodeToByteArray()

object PendingRoomStoreDefinitionV1 : StoreDefinition<LocalPendingRoom>(
    StoreName("pending_rooms"), "LocalPendingRoom-protobuf-v1", LocalPendingRoom.Companion::fromByteArray, LocalPendingRoom::toByteArray,
) {
    val ownerKey = bytesIndex(IndexName("ownerAccountIdUtf8"), LocalPendingRoom::ownerKeyStorage, "utf8-v1")
    val roomIdKey = bytesIndex(IndexName("roomId"), LocalPendingRoom::roomId, "raw-bytes-v1")
    val ownerRoomKey = compositeIndex(IndexName("ownerRoomKey"), ownerKey, roomIdKey).also { primaryKey(it) }
}
