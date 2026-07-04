package com.latenighthack.social.readreceipts.domain

import com.latenighthack.lockers.common.v1.LockerId
import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.lockers.connector.LockersClient
import com.latenighthack.lockers.connector.TypedLockerClient
import com.latenighthack.social.messages.domain.MessagesManager
import com.latenighthack.social.messages.v1.MessageId
import com.latenighthack.social.profiles.v1.ProfileId
import com.latenighthack.social.readreceipts.v1.ReadReceipt
import com.latenighthack.social.readreceipts.v1.fromByteArray
import com.latenighthack.social.readreceipts.v1.toByteArray
import com.latenighthack.social.rooms.domain.RoomsManager
import com.latenighthack.social.runtime.DomainLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * Keeps each member's read pointer as one [ReadReceipt] locker per member in a room's read-receipts
 * keyspace, keyed by profile id. Writes are authorized by the room's own lock (routed the shared room
 * key by the rooms key source), so read receipts need no key source of their own and the pointers are
 * stored unsigned — the same authorization story as messages and typing. [markRead] resolves the
 * room's most recent message from [MessagesManager] and advances the caller's pointer to it; the per
 * message "has read M" comparison is left to the reader, which has the message ordering. Nothing runs
 * in the background: [watchReadReceipts] subscribes the room lazily. Resumable: [start] captures the
 * client, [stop] releases it and leaves the manager reusable.
 */
class ReadReceiptsManagerImpl(
    private val rooms: RoomsManager,
    private val messages: MessagesManager,
) : ReadReceiptsManager, DomainLifecycle {

    private var lockers: LockersClient? = null

    override fun start(lockers: LockersClient) {
        this.lockers = lockers
    }

    override fun stop() {
        lockers = null
    }

    override suspend fun markRead(roomId: RoomId) {
        val me = rooms.localProfile(roomId) ?: return
        val latest = messages.watchMessageIds(roomId).first().lastOrNull() ?: return
        val client = readReceiptClient(requireLockers())
        val lockerId = LockerId(me.rawValue, ReadReceiptsKeyspaces.READ_RECEIPTS)
        // Skip a redundant write when the pointer already sits at the latest message — markRead is
        // called often (e.g. whenever the room is viewed) and each write is a network round-trip.
        if (client.getLocker(roomId, lockerId)?.messageId?.contentEquals(latest.rawValue) == true) return
        client.updateLocker(roomId, lockerId) { ReadReceipt { messageId = latest.rawValue } }
    }

    override fun watchReadReceipts(roomId: RoomId): Flow<Map<ProfileId, MessageId>> = flow {
        val client = readReceiptClient(requireLockers())
        emitAll(
            client.watchAll(roomId, ReadReceiptsKeyspaces.READ_RECEIPTS).map { receipts ->
                receipts.entries.associate { (lockerId, receipt) ->
                    ProfileId { rawValue = lockerId.rawValue } to MessageId(rawValue = receipt.messageId)
                }
            },
        )
    }.distinctUntilChanged()

    private fun requireLockers(): LockersClient = lockers ?: error("read receipts requires start(lockers) first")

    private fun readReceiptClient(lockers: LockersClient): TypedLockerClient<ReadReceipt> =
        lockers.typed(ReadReceiptsKeyspaces.READ_RECEIPTS, ReadReceipt::toByteArray, ReadReceipt.Companion::fromByteArray)
}
