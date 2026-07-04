package com.latenighthack.social.readreceipts.domain

import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.lockers.connector.LockersClient
import com.latenighthack.social.messages.v1.MessageId
import com.latenighthack.social.profiles.v1.ProfileId
import kotlinx.coroutines.flow.Flow

/**
 * Read receipts for rooms. Each member has a persistent locker (keyed by their profile id) in a
 * room's read-receipts keyspace whose body is a "read up to here" pointer — the message id of the
 * most recently read message. Writes are authorized by the room's own lock (routed the shared room
 * key by the rooms key source), so only members can mark read and no signing is needed.
 */
interface ReadReceiptsManager {
    fun start(lockers: LockersClient)
    fun stop()

    /**
     * Advances the caller's read pointer in [roomId] to the room's most recent message, as the
     * profile they are in the room as. A no-op if the room has no messages or the pointer is already
     * at the latest message.
     */
    suspend fun markRead(roomId: RoomId)

    /** Each member's read pointer in [roomId]: profile id → the most recently read message id. */
    fun watchReadReceipts(roomId: RoomId): Flow<Map<ProfileId, MessageId>>
}
