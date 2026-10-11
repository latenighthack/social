package com.latenighthack.social.readreceipts.domain

import com.latenighthack.social.observability.*

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
import com.latenighthack.social.runtime.AccountSession
import com.latenighthack.social.runtime.withAccount
import com.latenighthack.social.readreceipts.v1.copy
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
    private val myProfiles: com.latenighthack.social.profiles.domain.MyProfilesManager,
    private val scope: kotlinx.coroutines.CoroutineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default),
    private val session: AccountSession? = null,
) : ReadReceiptsManager, DomainLifecycle, SocialTelemetryOwner {
    override var socialTelemetry: SocialTelemetry = NoopSocialTelemetry

    override fun start(lockers: LockersClient) = run { socialTelemetry.event("read_receipts", "start"); (run observedOperation@ {
        runner.start(lockers) { kotlinx.coroutines.awaitCancellation() }

        }) }
    override fun stop() = run { socialTelemetry.event("read_receipts", "stop"); (run observedOperation@ {
        runner.stop()

        }) }
    override suspend fun markRead(roomId: RoomId): Unit = socialTelemetry.measure("read_receipts", "markRead") { (withSession {
        runner.command { markReadOwned(roomId) }
    }) }
    override fun watchReadReceipts(roomId: RoomId): Flow<Map<ProfileId, MessageId>>  = (flow {
        val client = readReceiptClient(requireLockers())
        emitAll(
            kotlinx.coroutines.flow.combine(client.watchAll(roomId, ReadReceiptsKeyspaces.READ_RECEIPTS), rooms.watchMembers(roomId)) { receipts, members ->
                receipts.entries.filter { (lockerId, receipt) ->
                    ProfileId(rawValue = lockerId.rawValue) in members && com.latenighthack.social.common.domain.verifyProfileClaim(lockerId.rawValue, roomId.rawValue,
                        receipt.profileId, receipt.roomId, receipt.proof, 4, receipt.copy(proof = null).toByteArray())
                }.associate { (lockerId, receipt) ->
                    ProfileId { rawValue = lockerId.rawValue } to MessageId(rawValue = receipt.messageId)
                }
            },
        )
    }.distinctUntilChanged()).socialObserved(socialTelemetry, "read_receipts")


    private val runner = com.latenighthack.social.runtime.ManagerRunner(scope)
    private val lockers: LockersClient? get() = runner.token as? LockersClient


    override suspend fun stopAndJoin() { runner.stopAndJoin() }


    private suspend fun markReadOwned(roomId: RoomId) {
        val me = rooms.localProfile(roomId) ?: return
        val ordered = messages.watchMessageIds(roomId).first()
        val latest = ordered.lastOrNull() ?: return
        val client = readReceiptClient(requireLockers())
        val lockerId = LockerId(me.rawValue, ReadReceiptsKeyspaces.READ_RECEIPTS)
        // Skip a redundant write when the pointer already sits at the latest message — markRead is
        // called often (e.g. whenever the room is viewed) and each write is a network round-trip.
        if (client.getLocker(roomId, lockerId)?.messageId?.contentEquals(latest.rawValue) == true) return
        val claim = ReadReceipt(messageId = latest.rawValue, roomId = roomId.rawValue, profileId = me.rawValue)
        val proof = myProfiles.sign(me, 4, claim.toByteArray()) ?: return
        com.latenighthack.social.runtime.rebasedUpdate(
            client.getLocker(roomId, lockerId) ?: ReadReceipt { },
            prepare = { current ->
                val verified = com.latenighthack.social.common.domain.verifyProfileClaim(me.rawValue, roomId.rawValue,
                    current.profileId, current.roomId, current.proof, 4, current.copy(proof = null).toByteArray())
                val comparison = if (verified) messages.compareMessageOrder(roomId, MessageId(rawValue = current.messageId), latest) else null
                // A verified pointer unknown to this device may be ahead; preserve it until sync.
                if (verified && (comparison == null || comparison >= 0)) current else claim.copy(proof = proof)
            },
            commit = { transform -> client.updateLocker(roomId, lockerId, builder = transform) },
        )
    }


    private suspend fun <T> withSession(block: suspend () -> T): T =
        if (session == null) block() else session.withAccount(block)

    private fun requireLockers(): LockersClient = lockers ?: error("read receipts requires start(lockers) first")

    private fun readReceiptClient(lockers: LockersClient): TypedLockerClient<ReadReceipt> =
        lockers.typed(ReadReceiptsKeyspaces.READ_RECEIPTS, ReadReceipt::toByteArray, ReadReceipt.Companion::fromByteArray)
}
