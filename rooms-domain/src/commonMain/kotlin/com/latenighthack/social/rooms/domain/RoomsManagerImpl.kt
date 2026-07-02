package com.latenighthack.social.rooms.domain

import com.latenighthack.ktcrypto.SHA256
import com.latenighthack.ktcrypto.Secp256r1KeyPair
import com.latenighthack.ktcrypto.digest
import com.latenighthack.ktcrypto.encode
import com.latenighthack.ktcrypto.fromPrivateKey
import com.latenighthack.ktcrypto.generate
import com.latenighthack.lockers.common.RoomKeying
import com.latenighthack.lockers.common.v1.LockScope
import com.latenighthack.lockers.common.v1.LockScopeKind
import com.latenighthack.lockers.common.v1.LockerId
import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.lockers.connector.LockersClient
import com.latenighthack.lockers.connector.TypedLockerClient
import com.latenighthack.lockers.connector.TypedLockerUpdate
import com.latenighthack.social.account.domain.AccountManager
import com.latenighthack.social.profiles.domain.MyProfilesManager
import com.latenighthack.social.profiles.v1.ProfileId
import com.latenighthack.social.rooms.v1.Invite
import com.latenighthack.social.rooms.v1.Member
import com.latenighthack.social.rooms.v1.MemberProfile
import com.latenighthack.social.rooms.v1.RoomInfo
import com.latenighthack.social.rooms.v1.RoomInfoBuilder
import com.latenighthack.social.rooms.v1.RoomKind
import com.latenighthack.social.rooms.v1.RoomRecord
import com.latenighthack.social.rooms.v1.SealedEnvelope
import com.latenighthack.social.rooms.v1.copy
import com.latenighthack.social.rooms.v1.fromByteArray
import com.latenighthack.social.rooms.v1.toByteArray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

/**
 * Owns the shared key material for every room the user belongs to and drives every room operation
 * over the lockers client passed to [start]. Its key material reaches the client through a
 * [RoomsKeySource] wrapping this manager.
 *
 * The room list — each [RoomRecord] (room id, kind, shared key, the profile the user is in as) — is
 * persisted as lockers in the user's own account room under the [RoomsKeyspaces.ACCOUNT_ROOMS]
 * keyspace, written on join/create and deleted on leave. Because the account room is synced and
 * locked to the account key, this is what lets a freshly restored account recover its rooms and
 * keys: at start, once the account is ready, the list is loaded from there.
 *
 * A rendezvous room's id and lock key are both derived from the ECDH of two profiles, so no key is
 * transmitted; a group's shared key is generated once and handed to invitees through a sealed
 * invite. Invites arrive in each profile's open, unlocked inbox keyspace and are unsealed with that
 * profile's key via [MyProfilesManager.deriveSharedSecret].
 */
class RoomsManagerImpl(
    private val account: AccountManager,
    private val myProfiles: MyProfilesManager,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : RoomsManager {

    // Room shared keys (immutable-swap for consistent reads from writeKey).
    private var keyPairs: Map<RoomId, Secp256r1KeyPair> = emptyMap()
    private var records: Map<RoomId, RoomRecord> = emptyMap()
    private val _rooms = MutableStateFlow<List<RoomId>>(emptyList())

    private val watchedInboxes = mutableSetOf<ProfileId>()
    private val processedInvites = mutableSetOf<LockerId>()

    private var job: Job? = null
    private var lockers: LockersClient? = null

    override fun start(lockers: LockersClient) {
        this.lockers = lockers
        if (job?.isActive == true) return
        job = scope.launch { run(lockers) }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    /** The shared write key for a room the user is a member of, or null (not our room → open/other). */
    internal fun writeKey(roomId: RoomId): Secp256r1KeyPair? = keyPairs[roomId]

    private suspend fun run(lockers: LockersClient) {
        // Restore the room list from the synced account room once the account is ready — this is
        // what makes a freshly restored account recover its rooms and shared keys.
        val ready = account.lifecycle.first { it is AccountManager.Lifecycle.Ready }
        loadRooms(lockers, (ready as AccountManager.Lifecycle.Ready).privateRoom)

        // Watch each of the user's profile inboxes for sealed invites, as profiles appear.
        myProfiles.getProfileList().collect { profileIds ->
            for (profileId in profileIds) {
                if (watchedInboxes.add(profileId)) {
                    scope.launch { watchInbox(lockers, profileId) }
                }
            }
        }
    }

    // --- room operations ---

    override suspend fun createGroup(name: String): RoomId {
        val lockers = lockers ?: error("createGroup requires start(lockers) first")
        val me = primaryProfileId()

        val groupKey = Secp256r1KeyPair.generate()
        val roomId = RoomKeying.publicKeyed(groupKey.publicKey.encode())
        adopt(lockers, RoomRecord(
            roomId = roomId.rawValue,
            kind = RoomKind.ROOM_KIND_GROUP,
            sharedPrivateKey = groupKey.privateKey.encode(),
            localProfileId = me.rawValue,
        ))

        // Public-keyed room: the root lock must be signed by the room authority (the group key).
        infoClient(lockers).lockLocker(
            roomId,
            LockScope(kind = LockScopeKind.LOCK_SCOPE_ROOM),
            groupKey,
            parentKeyPair = groupKey,
        )
        val groupName = name
        writeInfo(lockers, roomId, groupKey) { replaceDisclosure { name { value = groupName } } }
        writeMembership(lockers, roomId, me)
        return roomId
    }

    override suspend fun openRendezvous(peerProfileId: ProfileId): RoomId {
        val lockers = lockers ?: error("openRendezvous requires start(lockers) first")
        val me = primaryProfileId()

        val secret = myProfiles.deriveSharedSecret(me, peerProfileId.rawValue)
            ?: error("no primary profile to open a rendezvous with")
        val roomId = rendezvousRoomId(secret)
        val lockKey = rendezvousLockKey(secret) ?: error("could not derive rendezvous lock key")
        adopt(lockers, RoomRecord(
            roomId = roomId.rawValue,
            kind = RoomKind.ROOM_KIND_RENDEZVOUS,
            sharedPrivateKey = lockKey.privateKey.encode(),
            localProfileId = me.rawValue,
        ))

        // Opaque room: establish a TOFU root lock with the derived shared key (a no-op if the peer
        // locked it first — both sides derive the same key).
        infoClient(lockers).lockLocker(
            roomId,
            LockScope(kind = LockScopeKind.LOCK_SCOPE_ROOM),
            lockKey,
            parentKeyPair = null,
        )
        writeMembership(lockers, roomId, me)
        sendInvite(lockers, peerProfileId, Invite(
            kind = RoomKind.ROOM_KIND_RENDEZVOUS,
            inviterProfileId = me.rawValue,
        ))
        return roomId
    }

    override suspend fun invite(roomId: RoomId, inviteeProfileIds: List<ProfileId>) {
        val lockers = lockers ?: error("invite requires start(lockers) first")
        val record = records[roomId] ?: error("not a member of this room")
        require(record.kind == RoomKind.ROOM_KIND_GROUP) { "only group rooms are invited to; rendezvous is 1:1" }
        val invite = Invite(
            kind = RoomKind.ROOM_KIND_GROUP,
            roomId = roomId.rawValue,
            groupPrivateKey = record.sharedPrivateKey,
        )
        // Each recipient gets its own envelope, sealed to that profile's key.
        for (inviteeProfileId in inviteeProfileIds) {
            sendInvite(lockers, inviteeProfileId, invite)
        }
    }

    override suspend fun leave(roomId: RoomId) {
        val lockers = lockers ?: return
        val record = records[roomId] ?: return
        val me = ProfileId { rawValue = record.localProfileId }
        // Delete the in-room entries while the shared key is still routed for this room, then drop
        // it locally and from the synced account-room list (the latter signed by the account key).
        membershipClient(lockers).deleteLocker(roomId, LockerId(me.rawValue, RoomsKeyspaces.MEMBERSHIP))
        memberProfileClient(lockers).deleteLocker(roomId, LockerId(me.rawValue, RoomsKeyspaces.MEMBER_PROFILES))
        records = records - roomId
        keyPairs = keyPairs - roomId
        _rooms.value = sortedRoomIds()
        accountRoom()?.let { accountRoom ->
            accountRoomsClient(lockers).deleteLocker(accountRoom, LockerId(roomId.rawValue, RoomsKeyspaces.ACCOUNT_ROOMS))
        }
    }

    override suspend fun updateInfo(roomId: RoomId, builder: RoomInfoBuilder.() -> Unit) {
        val lockers = lockers ?: error("updateInfo requires start(lockers) first")
        val roomKey = keyPairs[roomId] ?: error("not a member of this room")
        writeInfo(lockers, roomId, roomKey, builder)
    }

    override suspend fun markUpdated(roomId: RoomId) {
        val lockers = lockers ?: return
        val record = records[roomId] ?: return
        val bumped = record.copy(updatedAtMillis = Clock.System.now().toEpochMilliseconds())
        records = records + (roomId to bumped)
        _rooms.value = sortedRoomIds()
        accountRoom()?.let { accountRoom ->
            accountRoomsClient(lockers).updateLocker(
                accountRoom,
                LockerId(roomId.rawValue, RoomsKeyspaces.ACCOUNT_ROOMS),
            ) { bumped }
        }
    }

    override fun watchRooms(): Flow<List<RoomId>> = _rooms

    override fun watchInfo(roomId: RoomId): Flow<RoomInfo?> =
        infoClient(requireLockers()).watch(roomId, RoomsKeyspaces.ROOM_INFO_LOCKER).map {
            when (it) {
                is TypedLockerUpdate.Present -> it.value
                is TypedLockerUpdate.Deleted -> null
            }
        }.distinctUntilChanged()

    override fun watchMembers(roomId: RoomId): Flow<List<ProfileId>> =
        membershipClient(requireLockers()).watchAll(roomId, RoomsKeyspaces.MEMBERSHIP).map { members ->
            members.keys.map { ProfileId { rawValue = it.rawValue } }
        }.distinctUntilChanged()

    // --- invite delivery + inbox ---

    private suspend fun sendInvite(lockers: LockersClient, recipient: ProfileId, invite: Invite) {
        val envelope = RoomSealing.seal(recipient.rawValue, invite.toByteArray())
        val inboxRoom = RoomKeying.publicKeyed(recipient.rawValue)
        // The inbox keyspace is unlocked, so this write stays open (no signing key is routed for it).
        // The locker id is sha256 of the random ephemeral key: unlinkable and unique per invite.
        val lockerId = LockerId(SHA256.digest(envelope.ephemeralPublicKey), RoomsKeyspaces.INBOX)
        val inbox = inboxClient(lockers)
        inbox.subscribeToRoom(inboxRoom)
        inbox.updateLocker(inboxRoom, lockerId) { envelope }
    }

    private suspend fun watchInbox(lockers: LockersClient, profileId: ProfileId) {
        val inboxRoom = RoomKeying.publicKeyed(profileId.rawValue)
        inboxClient(lockers).watchAll(inboxRoom, RoomsKeyspaces.INBOX).collect { envelopes ->
            for ((lockerId, envelope) in envelopes) {
                if (!processedInvites.add(lockerId)) continue
                processInvite(lockers, profileId, envelope)
            }
        }
    }

    private suspend fun processInvite(lockers: LockersClient, profileId: ProfileId, envelope: SealedEnvelope) {
        val secret = myProfiles.deriveSharedSecret(profileId, envelope.ephemeralPublicKey) ?: return
        val payload = RoomSealing.unsealWith(secret, envelope) ?: return
        val invite = Invite.fromByteArray(payload)
        when (invite.kind) {
            RoomKind.ROOM_KIND_GROUP -> {
                val roomId = RoomId(rawValue = invite.roomId)
                if (records.containsKey(roomId)) return
                adopt(lockers, RoomRecord(
                    roomId = invite.roomId,
                    kind = RoomKind.ROOM_KIND_GROUP,
                    sharedPrivateKey = invite.groupPrivateKey,
                    localProfileId = profileId.rawValue,
                ))
                writeMembership(lockers, roomId, profileId)
            }
            RoomKind.ROOM_KIND_RENDEZVOUS -> {
                val secretWithInviter = myProfiles.deriveSharedSecret(profileId, invite.inviterProfileId) ?: return
                val roomId = rendezvousRoomId(secretWithInviter)
                if (records.containsKey(roomId)) return
                val lockKey = rendezvousLockKey(secretWithInviter) ?: return
                adopt(lockers, RoomRecord(
                    roomId = roomId.rawValue,
                    kind = RoomKind.ROOM_KIND_RENDEZVOUS,
                    sharedPrivateKey = lockKey.privateKey.encode(),
                    localProfileId = profileId.rawValue,
                ))
                infoClient(lockers).lockLocker(
                    roomId,
                    LockScope(kind = LockScopeKind.LOCK_SCOPE_ROOM),
                    lockKey,
                    parentKeyPair = null,
                )
                writeMembership(lockers, roomId, profileId)
            }
            else -> return
        }
    }

    // --- shared helpers ---

    /** Load the synced room list from the account room and rebuild in-memory state for each. */
    private suspend fun loadRooms(lockers: LockersClient, accountRoom: RoomId) {
        val client = accountRoomsClient(lockers)
        client.subscribeToRoom(accountRoom)
        for ((_, record) in client.getAllLockers(accountRoom)) {
            remember(lockers, record)
        }
    }

    /**
     * Take on a room the user is now in: set its key material and record in memory, (re)subscribe,
     * and record it in the synced account-room list so a fresh restore recovers it. Key material is
     * set before any write so [RoomsKeySource] can sign for the room.
     */
    private suspend fun adopt(lockers: LockersClient, record: RoomRecord) {
        // Stamp the join/create time so a newly adopted room sorts to the front of the list.
        val stamped = record.copy(updatedAtMillis = Clock.System.now().toEpochMilliseconds())
        remember(lockers, stamped)
        accountRoom()?.let { accountRoom ->
            accountRoomsClient(lockers).updateLocker(
                accountRoom,
                LockerId(stamped.roomId, RoomsKeyspaces.ACCOUNT_ROOMS),
            ) { stamped }
        }
    }

    /** Set in-memory key material + record and (re)subscribe. Idempotent; no account-room write. */
    private suspend fun remember(lockers: LockersClient, record: RoomRecord) {
        val roomId = RoomId(rawValue = record.roomId)
        val keyPair = Secp256r1KeyPair.fromPrivateKey(record.sharedPrivateKey) ?: return
        keyPairs = keyPairs + (roomId to keyPair)
        records = records + (roomId to record)
        infoClient(lockers).subscribeToRoom(roomId)
        _rooms.value = sortedRoomIds()
    }

    /** The user's room ids ordered by `updated_at`, newest first. */
    private fun sortedRoomIds(): List<RoomId> =
        records.entries.sortedByDescending { it.value.updatedAtMillis }.map { it.key }

    private fun accountRoom(): RoomId? =
        (account.lifecycle.value as? AccountManager.Lifecycle.Ready)?.privateRoom

    private suspend fun writeInfo(
        lockers: LockersClient,
        roomId: RoomId,
        roomKey: Secp256r1KeyPair,
        builder: RoomInfoBuilder.() -> Unit,
    ) {
        val client = infoClient(lockers)
        // Apply the caller's builder to the current info, then re-sign every disclosure over its
        // payload with the shared room key so signatures always match the written content.
        val built = (client.getLocker(roomId, RoomsKeyspaces.ROOM_INFO_LOCKER) ?: RoomInfo { }).copy(builder)
        val signed = built.disclosures.map {
            RoomInfoDisclosures.sign(roomKey, roomId, it.payload ?: RoomInfo.Disclosure.Payload())
        }
        val updated = built.copy { disclosures = signed }
        client.updateLocker(roomId, RoomsKeyspaces.ROOM_INFO_LOCKER) { updated }
    }

    private suspend fun writeMembership(lockers: LockersClient, roomId: RoomId, profileId: ProfileId) {
        val now = Clock.System.now().toEpochMilliseconds()
        membershipClient(lockers).updateLocker(roomId, LockerId(profileId.rawValue, RoomsKeyspaces.MEMBERSHIP)) {
            Member(joinedAtMillis = now)
        }
        memberProfileClient(lockers).updateLocker(roomId, LockerId(profileId.rawValue, RoomsKeyspaces.MEMBER_PROFILES)) {
            MemberProfile(profileId = profileId.rawValue)
        }
    }

    private suspend fun primaryProfileId(): ProfileId =
        myProfiles.getProfileList().first().firstOrNull() ?: error("a profile is required to use rooms")

    private suspend fun rendezvousRoomId(secret: ByteArray): RoomId =
        RoomId(rawValue = SHA256.digest(RENDEZVOUS_ROOM_DOMAIN + secret))

    private suspend fun rendezvousLockKey(secret: ByteArray): Secp256r1KeyPair? =
        Secp256r1KeyPair.fromPrivateKey(SHA256.digest(RENDEZVOUS_LOCK_DOMAIN + secret))

    private fun requireLockers(): LockersClient = lockers ?: error("start(lockers) is required first")

    private fun infoClient(lockers: LockersClient): TypedLockerClient<RoomInfo> =
        lockers.typed(RoomsKeyspaces.ROOM_INFO, RoomInfo::toByteArray, RoomInfo.Companion::fromByteArray)

    private fun membershipClient(lockers: LockersClient): TypedLockerClient<Member> =
        lockers.typed(RoomsKeyspaces.MEMBERSHIP, Member::toByteArray, Member.Companion::fromByteArray)

    private fun memberProfileClient(lockers: LockersClient): TypedLockerClient<MemberProfile> =
        lockers.typed(RoomsKeyspaces.MEMBER_PROFILES, MemberProfile::toByteArray, MemberProfile.Companion::fromByteArray)

    private fun inboxClient(lockers: LockersClient): TypedLockerClient<SealedEnvelope> =
        lockers.typed(RoomsKeyspaces.INBOX, SealedEnvelope::toByteArray, SealedEnvelope.Companion::fromByteArray)

    private fun accountRoomsClient(lockers: LockersClient): TypedLockerClient<RoomRecord> =
        lockers.typed(RoomsKeyspaces.ACCOUNT_ROOMS, RoomRecord::toByteArray, RoomRecord.Companion::fromByteArray)

    private companion object {
        // Domain-separated KDF prefixes so the rendezvous room id and lock key are independent.
        val RENDEZVOUS_ROOM_DOMAIN = "social.rooms.rendezvous.room.v1".encodeToByteArray()
        val RENDEZVOUS_LOCK_DOMAIN = "social.rooms.rendezvous.lock.v1".encodeToByteArray()
    }
}
