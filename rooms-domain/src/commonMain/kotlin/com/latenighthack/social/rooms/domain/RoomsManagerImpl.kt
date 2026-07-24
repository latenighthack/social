// kotlin.time.Clock replaces kotlinx-datetime's (removed in datetime 0.7): stdlib-only, still
// experimental on Kotlin 2.2.x. Only .now().toEpochMilliseconds() is used.
@file:OptIn(kotlin.time.ExperimentalTime::class)

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
import com.latenighthack.social.common.domain.Sealing
import com.latenighthack.social.common.v1.SealedEnvelope
import com.latenighthack.social.common.v1.fromByteArray
import com.latenighthack.social.common.v1.toByteArray
import com.latenighthack.social.profiles.domain.MyProfilesManager
import com.latenighthack.social.runtime.DomainLifecycle
import com.latenighthack.social.profiles.v1.ProfileId
import com.latenighthack.social.rooms.v1.CreateInviteCodeRequest
import com.latenighthack.social.rooms.v1.Invite
import com.latenighthack.social.rooms.v1.InviteCode
import com.latenighthack.social.rooms.v1.JoinRequest
import com.latenighthack.social.rooms.v1.JoinResult
import com.latenighthack.social.rooms.v1.Member
import com.latenighthack.social.rooms.v1.MemberProfile
import com.latenighthack.social.rooms.v1.RevokeInviteCodeRequest
import com.latenighthack.social.rooms.v1.RoomInfo
import com.latenighthack.social.rooms.v1.RoomInfoBuilder
import com.latenighthack.social.rooms.v1.RoomKind
import com.latenighthack.social.rooms.v1.RoomRecord
import com.latenighthack.social.rooms.v1.copy
import com.latenighthack.social.rooms.v1.fromByteArray
import com.latenighthack.social.rooms.v1.toByteArray
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

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
 * transmitted; its bootstrap invite arrives in the peer's open, unlocked inbox keyspace and is
 * unsealed with that profile's key via [MyProfilesManager.deriveSharedSecret]. Group access
 * goes through the server-mediated [JoinClient]: a member mints a revocable invite code (handing the
 * server the group key), and a joiner redeems it for a grant sealed to their own profile. A member
 * can also invite a peer directly ([inviteToRoom]): the same group grant is sealed straight into
 * the peer's profile inbox and the peer's manager auto-joins on receipt.
 */
class RoomsManagerImpl(
    private val account: AccountManager,
    private val myProfiles: MyProfilesManager,
    private val joinClient: JoinClient,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : RoomsManager, DomainLifecycle {

    // Room shared keys (immutable-swap for consistent reads from writeKey). The swaps themselves are
    // guarded by [stateMutex] so concurrent inbox collectors and callers don't lose each other's
    // updates; reads stay lock-free (each read sees a complete immutable snapshot).
    private var keyPairs: Map<RoomId, Secp256r1KeyPair> = emptyMap()
    private var records: Map<RoomId, RoomRecord> = emptyMap()
    private val _rooms = MutableStateFlow<List<RoomId>>(emptyList())
    private val stateMutex = Mutex()

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
        // A prior stop() cancelled the inbox collectors, so forget which inboxes were being watched
        // and re-establish them below (the processedInvites dedup cache is deliberately retained).
        watchedInboxes.clear()

        // Watch each of the user's profile inboxes for sealed invites, as profiles appear. The
        // per-inbox collectors are launched as children of this coroutine (not the retained scope)
        // so stop() — which cancels this job — tears them down too; supervisorScope keeps one
        // collector's failure from cancelling the others.
        supervisorScope {
            launch {
                // Restore the room list from the synced account room — this is what makes a freshly
                // restored account recover its rooms and shared keys. Ready arrives offline too
                // (cache-backed) and each reconnect re-emits it, so reload on every emission: an
                // offline cold-cache load legitimately sees nothing, and the reconnect tick then
                // picks up the server copy. loadRooms is idempotent; failures must not kill this
                // collector.
                account.lifecycle.collect { lifecycle ->
                    if (lifecycle is AccountManager.Lifecycle.Ready) {
                        runCatching { loadRooms(lockers, lifecycle.privateRoom) }
                    }
                }
            }

            myProfiles.getProfileList().collect { profileIds ->
                for (profileId in profileIds) {
                    if (watchedInboxes.add(profileId)) {
                        launch { watchInbox(lockers, profileId) }
                    }
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

    override suspend fun deriveChildRoomId(parentRoomId: RoomId, purpose: String, salt: ByteArray): RoomId =
        RoomKeying.publicKeyed(deriveChildKey(parentRoomId, purpose, salt).publicKey.encode())

    override suspend fun openDerivedRoom(parentRoomId: RoomId, purpose: String, salt: ByteArray): RoomId {
        val lockers = requireLockers()
        val parent = records[parentRoomId] ?: error("not a member of the parent room")
        val me = ProfileId(rawValue = parent.localProfileId)

        val childKey = deriveChildKey(parentRoomId, purpose, salt)
        val roomId = RoomKeying.publicKeyed(childKey.publicKey.encode())
        if (!records.containsKey(roomId)) {
            adopt(lockers, RoomRecord(
                roomId = roomId.rawValue,
                kind = RoomKind.ROOM_KIND_GROUP,
                sharedPrivateKey = childKey.privateKey.encode(),
                localProfileId = me.rawValue,
            ))
        }
        // Public-keyed room: the root lock must be signed by the room authority (the derived key).
        // Every parent member derives the same key, so a repeat lock is byte-identical to the first.
        infoClient(lockers).lockLocker(
            roomId,
            LockScope(kind = LockScopeKind.LOCK_SCOPE_ROOM),
            childKey,
            parentKeyPair = childKey,
        )
        writeMembership(lockers, roomId, me)
        return roomId
    }

    private suspend fun deriveChildKey(parentRoomId: RoomId, purpose: String, salt: ByteArray): Secp256r1KeyPair {
        val record = records[parentRoomId] ?: error("not a member of the parent room")
        val seed = SHA256.digest(
            DERIVED_ROOM_DOMAIN + purpose.encodeToByteArray() + record.sharedPrivateKey + salt,
        )
        return Secp256r1KeyPair.fromPrivateKey(seed) ?: error("could not derive a child room key")
    }

    override suspend fun createInviteCode(roomId: RoomId): InviteCode {
        val record = records[roomId] ?: error("not a member of this room")
        require(record.kind == RoomKind.ROOM_KIND_GROUP) { "invite codes are for group rooms; rendezvous is 1:1" }
        // Hand the server the shared key so it can seal per-joiner grants; whoever holds the key is a
        // member, and the server checks it really belongs to this room before retaining it.
        val response = joinClient.createInviteCode(CreateInviteCodeRequest {
            this.roomId = roomId.rawValue
            groupPrivateKey = record.sharedPrivateKey
        })
        check(response.result == JoinResult.JOIN_RESULT_OK) { "invite code creation failed: ${response.result}" }
        return response.code ?: error("invite code creation returned no code")
    }

    override suspend fun revokeInviteCode(roomId: RoomId, code: InviteCode) {
        val record = records[roomId] ?: return
        joinClient.revokeInviteCode(RevokeInviteCodeRequest {
            this.code = code
            groupPrivateKey = record.sharedPrivateKey
        })
    }

    override suspend fun inviteToRoom(roomId: RoomId, peerProfileId: ProfileId) {
        val lockers = lockers ?: error("inviteToRoom requires start(lockers) first")
        val record = records[roomId] ?: error("not a member of this room")
        require(record.kind == RoomKind.ROOM_KIND_GROUP) { "direct invites are for group rooms; rendezvous is 1:1" }
        sendInvite(lockers, peerProfileId, Invite(
            kind = RoomKind.ROOM_KIND_GROUP,
            inviterProfileId = record.localProfileId,
            roomId = roomId.rawValue,
            groupPrivateKey = record.sharedPrivateKey,
        ))
    }

    override suspend fun joinByCode(code: InviteCode): RoomId {
        val lockers = lockers ?: error("joinByCode requires start(lockers) first")
        val me = primaryProfileId()
        val response = joinClient.join(JoinRequest {
            this.code = code
            inviteeProfileId = me.rawValue
        })
        check(response.result == JoinResult.JOIN_RESULT_OK) { "join failed: ${response.result}" }
        val sealed = response.sealedInvite ?: error("join returned no grant")

        // Only our profile key can unseal the grant the server sealed to us.
        val secret = myProfiles.deriveSharedSecret(me, sealed.ephemeralPublicKey) ?: error("cannot unseal grant")
        val invite = Invite.fromByteArray(Sealing.unsealWith(secret, sealed) ?: error("cannot unseal grant"))
        require(invite.kind == RoomKind.ROOM_KIND_GROUP) { "unexpected grant kind: ${invite.kind}" }

        // Bind the sealed key to its claimed room so a grant can't be re-pointed at another room.
        val groupKey = Secp256r1KeyPair.fromPrivateKey(invite.groupPrivateKey) ?: error("grant carries an invalid key")
        require(RoomKeying.publicKeyed(groupKey.publicKey.encode()).rawValue.contentEquals(invite.roomId)) {
            "grant key does not match its room"
        }

        val roomId = RoomId(rawValue = invite.roomId)
        if (records.containsKey(roomId)) return roomId
        adopt(lockers, RoomRecord(
            roomId = invite.roomId,
            kind = RoomKind.ROOM_KIND_GROUP,
            sharedPrivateKey = invite.groupPrivateKey,
            localProfileId = me.rawValue,
        ))
        writeMembership(lockers, roomId, me)
        return roomId
    }

    override suspend fun leave(roomId: RoomId) {
        val lockers = lockers ?: return
        val record = records[roomId] ?: return
        val me = ProfileId { rawValue = record.localProfileId }
        // Delete the in-room entries while the shared key is still routed for this room, then drop
        // it locally and from the synced account-room list (the latter signed by the account key).
        membershipClient(lockers).deleteLocker(roomId, LockerId(me.rawValue, RoomsKeyspaces.MEMBERSHIP))
        memberProfileClient(lockers).deleteLocker(roomId, LockerId(me.rawValue, RoomsKeyspaces.MEMBER_PROFILES))
        stateMutex.withLock {
            records = records - roomId
            keyPairs = keyPairs - roomId
            _rooms.value = sortedRoomIds()
        }
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
        val bumped = stateMutex.withLock {
            val record = records[roomId] ?: return
            record.copy(updatedAtMillis = Clock.System.now().toEpochMilliseconds()).also {
                records = records + (roomId to it)
                _rooms.value = sortedRoomIds()
            }
        }
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
                is TypedLockerUpdate.Present -> verifyInfo(roomId, it.value)
                is TypedLockerUpdate.Deleted -> null
            }
        }.distinctUntilChanged()

    /** Keep only info disclosures carrying a valid signature by the shared room key we hold. */
    private suspend fun verifyInfo(roomId: RoomId, info: RoomInfo): RoomInfo {
        val roomKey = keyPairs[roomId] ?: return info
        val kept = info.disclosures.filter { RoomInfoDisclosures.verify(it, roomKey.publicKey) }
        return info.copy { disclosures = kept }
    }

    override fun watchMembers(roomId: RoomId): Flow<List<ProfileId>> =
        membershipClient(requireLockers()).watchAll(roomId, RoomsKeyspaces.MEMBERSHIP).map { members ->
            members.keys.map { ProfileId { rawValue = it.rawValue } }
        }.distinctUntilChanged()

    override fun localProfile(roomId: RoomId): ProfileId? =
        records[roomId]?.let { ProfileId { rawValue = it.localProfileId } }

    override fun roomKind(roomId: RoomId): RoomKind? = records[roomId]?.kind

    // --- invite delivery + inbox ---

    private suspend fun sendInvite(lockers: LockersClient, recipient: ProfileId, invite: Invite) {
        val envelope = Sealing.seal(recipient.rawValue, invite.toByteArray())
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
                if (stateMutex.withLock { lockerId in processedInvites }) continue
                // Mark the invite processed only once it is fully handled, and never let a malformed or
                // transiently-failing invite (e.g. a write lost to shutdown) tear down this collector —
                // leaving it unprocessed lets a later emission retry it. processInvite is idempotent.
                try {
                    processInvite(lockers, profileId, envelope)
                    stateMutex.withLock { processedInvites.add(lockerId) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        }
    }

    private suspend fun processInvite(lockers: LockersClient, profileId: ProfileId, envelope: SealedEnvelope) {
        val secret = myProfiles.deriveSharedSecret(profileId, envelope.ephemeralPublicKey) ?: return
        val payload = Sealing.unsealWith(secret, envelope) ?: return
        // The plaintext is attacker-chosen (anyone can seal to our inbox), so a malformed invite must
        // be skipped rather than allowed to crash this inbox collector.
        val invite = runCatching { Invite.fromByteArray(payload) }.getOrNull() ?: return
        when (invite.kind) {
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
            RoomKind.ROOM_KIND_GROUP -> {
                // Same binding check as joinByCode: the sealed key must actually key the claimed
                // room, so an invite cannot be re-pointed at another room.
                val groupKey = Secp256r1KeyPair.fromPrivateKey(invite.groupPrivateKey) ?: return
                if (!RoomKeying.publicKeyed(groupKey.publicKey.encode()).rawValue.contentEquals(invite.roomId)) return
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
            else -> return
        }
    }

    // --- shared helpers ---

    /** Load the synced room list from the account room and rebuild in-memory state for each. */
    private suspend fun loadRooms(lockers: LockersClient, accountRoom: RoomId) {
        val client = accountRoomsClient(lockers)
        // no ACK wait: offline, the cached room list must still load (reconnect reconciles the sub)
        client.subscribeToRoom(accountRoom, waitForSubscription = false)
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
        stateMutex.withLock {
            keyPairs = keyPairs + (roomId to keyPair)
            records = records + (roomId to record)
            _rooms.value = sortedRoomIds()
        }
        infoClient(lockers).subscribeToRoom(roomId, waitForSubscription = false)
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
            RoomInfoDisclosures.sign(roomKey, roomId, RoomInfo.DisclosurePayload.fromByteArray(it.content))
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

        // KDF prefix for deterministic child rooms (openDerivedRoom); the caller's purpose string
        // further separates uses within it.
        val DERIVED_ROOM_DOMAIN = "social.rooms.derived.room.v1".encodeToByteArray()
    }
}
