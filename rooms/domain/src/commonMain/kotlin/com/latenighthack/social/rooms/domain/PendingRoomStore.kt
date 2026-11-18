package com.latenighthack.social.rooms.domain

import com.latenighthack.ktstore.*
import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.social.rooms.v1.LocalPendingRoom

/**
 * Device-local durable record of groups this device created whose server setup hasn't finished.
 * Written before createGroup returns and deleted once the room and its account record are on the
 * server, so a crash mid-create resumes on the next start. Keyed by (owner account, room id).
 */
internal class PendingRoomStore(database: Database) : Store<LocalPendingRoom>(database, PendingRoomStoreDefinitionV1) {
    private val ownerKey = PendingRoomStoreDefinitionV1.ownerKey
    private val roomIdKey = PendingRoomStoreDefinitionV1.roomIdKey

    private fun key(owner: String, roomId: RoomId) = PendingRoomStoreDefinitionV1.ownerRoomKey.eq(listOf(
        BoundStoreKey.SerializedKey(ownerKey.name.value, owner.encodeToByteArray()),
        BoundStoreKey.SerializedKey(roomIdKey.name.value, roomId.rawValue)))

    suspend fun pendingFor(owner: String): List<LocalPendingRoom> = getAll(ownerKey.eq(owner.encodeToByteArray()))

    suspend fun savePending(pending: LocalPendingRoom) = save(pending)

    suspend fun deletePending(owner: String, roomId: RoomId) = delete(key(owner, roomId))
}
