package com.latenighthack.social.rooms.domain

import com.latenighthack.ktstore.Store
import com.latenighthack.ktstore.StoreDelegate
import com.latenighthack.social.rooms.v1.RoomRecord
import com.latenighthack.social.rooms.v1.fromByteArray
import com.latenighthack.social.rooms.v1.toByteArray

/**
 * Device-local persistent record of the rooms the user belongs to, keyed by room id. Holds the
 * shared write key material and is NEVER synced — it lives only here, like the account record.
 */
internal class RoomStore(delegate: StoreDelegate) : Store<RoomRecord>(
    delegate,
    "rooms",
    RoomRecord::toByteArray,
    RoomRecord.Companion::fromByteArray,
) {
    private val roomIdKey = serializedIndex(RoomRecord::roomId, ::identityBytes)
        .also { primaryKey(it) }

    suspend fun getAllRecords(): List<RoomRecord> = getAll()

    suspend fun saveRecord(record: RoomRecord) = save(record)

    suspend fun removeRecord(roomId: ByteArray) = delete(roomIdKey.eq(roomId))
}

/** Identity serializer: the room id is already the stored bytes. */
private fun identityBytes(value: ByteArray): ByteArray = value
