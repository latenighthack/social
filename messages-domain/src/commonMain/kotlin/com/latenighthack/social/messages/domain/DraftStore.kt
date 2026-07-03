package com.latenighthack.social.messages.domain

import com.latenighthack.ktstore.Store
import com.latenighthack.ktstore.StoreDelegate
import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.social.messages.v1.LocalDraft
import com.latenighthack.social.messages.v1.fromByteArray
import com.latenighthack.social.messages.v1.toByteArray

/**
 * Device-local persistent store of per-room drafts, wrapped as [LocalDraft] (room id + draft).
 * One draft per room, keyed by the room id's raw bytes. Never leaves local storage.
 */
internal class DraftStore(delegate: StoreDelegate) : Store<LocalDraft>(
    delegate,
    "drafts",
    LocalDraft::toByteArray,
    LocalDraft.Companion::fromByteArray,
) {
    private val roomIdKey = bytesIndex(LocalDraft::roomId).also { primaryKey(it) }

    suspend fun getAllDrafts(): List<LocalDraft> = getAll()

    suspend fun saveDraft(draft: LocalDraft) = save(draft)

    suspend fun removeDraft(roomId: RoomId) = delete(roomIdKey.eq(roomId.rawValue))
}
