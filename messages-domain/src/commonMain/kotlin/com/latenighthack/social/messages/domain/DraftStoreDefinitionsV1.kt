package com.latenighthack.social.messages.domain
import com.latenighthack.ktstore.*
import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.social.messages.v1.LocalDraft
import com.latenighthack.social.messages.v1.fromByteArray
import com.latenighthack.social.messages.v1.toByteArray

object DraftStoreDefinitionV1 : StoreDefinition<LocalDraft>(
    StoreName("drafts"), "LocalDraft-protobuf-v1", LocalDraft.Companion::fromByteArray, LocalDraft::toByteArray,
) {
    val roomIdKey = bytesIndex(IndexName("roomId"), LocalDraft::roomId, "raw-bytes-v1").also { primaryKey(it) }
}
