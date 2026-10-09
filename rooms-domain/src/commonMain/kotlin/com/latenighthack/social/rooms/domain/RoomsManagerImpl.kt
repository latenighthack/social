// kotlin.time.Clock replaces kotlinx-datetime's (removed in datetime 0.7): stdlib-only, still
// experimental on Kotlin 2.2.x. Only .now().toEpochMilliseconds() is used.
@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.latenighthack.social.rooms.domain

import kotlinx.coroutines.flow.asStateFlow

import com.latenighthack.social.runtime.withAccount
import com.latenighthack.social.runtime.requireOperationOwner

import com.latenighthack.ktcrypto.SHA256
import com.latenighthack.ktcrypto.Secp256r1KeyPair
import com.latenighthack.ktcrypto.digest
import com.latenighthack.ktcrypto.decode
import com.latenighthack.ktcrypto.encode
import com.latenighthack.ktcrypto.fromPrivateKey
import com.latenighthack.ktcrypto.generate
import com.latenighthack.lockers.common.RoomKeying
import com.latenighthack.lockers.common.v1.LockScope
import com.latenighthack.lockers.common.v1.LockScopeKind
import com.latenighthack.lockers.common.v1.LockerId
import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.lockers.connector.LockerClient
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import com.latenighthack.social.runtime.TaskHealth
import com.latenighthack.social.runtime.recoverTask
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.coroutineScope
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
    private val invitePolicy: RoomInvitePolicy = AcceptRoomInvites,
) : RoomsManager, DomainLifecycle {

    // Room shared keys (immutable-swap for consistent reads from writeKey). The swaps themselves are
    // guarded by [stateMutex] so concurrent inbox collectors and callers don't lose each other's
    // updates; reads stay lock-free (each read sees a complete immutable snapshot).
    private var keyPairs: Map<RoomId, Secp256r1KeyPair> = emptyMap()
    private var records: Map<RoomId, RoomRecord> = emptyMap()
    private val _rooms = MutableStateFlow<List<RoomId>>(emptyList())
    private val stateMutex = Mutex()

    private val processedInvites = linkedSetOf<LockerId>()
    private var leftRooms: Set<RoomId> = emptySet()
    private val loadedOwner = MutableStateFlow<String?>(null)
    private val loadedGeneration = MutableStateFlow(-1L)
    private fun ownsKeys() = account.owner.value != null && account.owner.value == loadedOwner.value &&
        account.generation.value == loadedGeneration.value

    private val mutableTaskHealth = kotlinx.coroutines.flow.MutableStateFlow<TaskHealth>(TaskHealth.Idle)
    override val taskHealth = mutableTaskHealth.asStateFlow()
    private val runner = com.latenighthack.social.runtime.ManagerRunner(scope)
    private val lockers: LockersClient? get() = runner.token as? LockersClient

    override fun start(lockers: LockersClient) {
        runner.start(lockers) {
             recoverTask(mutableTaskHealth) { run(lockers) } }
    }

    override fun stop() {
        runner.stop()
    }

    override suspend fun stopAndJoin() {
        runner.stopAndJoin()
    }

    /** The shared write key for a room the user is a member of, or null (not our room → open/other). */
    internal fun writeKey(roomId: RoomId): Secp256r1KeyPair? = if (ownsKeys()) (keyPairs[roomId] ?: cleanupKeys.value[roomId]) else null

    private suspend fun run(lockers: LockersClient) {
        // A prior stop() cancelled the inbox collectors, so forget which inboxes were being watched
        // and re-establish them below (the processedInvites dedup cache is deliberately retained).

        // Watch each of the user's profile inboxes for sealed invites, as profiles appear. The
        // per-inbox collectors are launched as children of this coroutine (not the retained scope)
        // so stop() — which cancels this job — tears them down too; supervisorScope keeps one
        // collector's failure from cancelling the others.
        coroutineScope {
            launch {
                // Restore the room list from the synced account room — this is what makes a freshly
                // restored account recover its rooms and shared keys. Ready arrives offline too
                // (cache-backed) and each reconnect re-emits it, so reload on every emission: an
                // offline cold-cache load legitimately sees nothing, and the reconnect tick then
                // picks up the server copy. loadRooms is idempotent; failures must not kill this
                // collector.
                combine(account.lifecycle, account.generation) { state, epoch -> (state as? AccountManager.Lifecycle.Ready) to epoch }
                    .distinctUntilChanged { old, new ->
                        old.first?.accountId?.toList() == new.first?.accountId?.toList() && old.second == new.second
                    }.collectLatest { (ready, epoch) ->
                val accountRoom = ready?.privateRoom
                        stateMutex.withLock {
                            keyPairs = emptyMap(); records = emptyMap(); leftRooms = emptySet()
                            _rooms.value = emptyList()
                        }
                        loadedOwner.value = ready?.accountId?.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
                        loadedGeneration.value = epoch
                        if (loadedOwner.value != account.owner.value || epoch != account.generation.value) return@collectLatest
                        if (accountRoom == null) return@collectLatest
                        var previous = emptySet<RoomId>()
                        accountRoomsClient(lockers).watchAll(accountRoom).collect { source ->
                            val ids = source.values.map { RoomId(rawValue = it.roomId) }.toSet()
                            stateMutex.withLock {
                                val removed = previous - ids
                                keyPairs = keyPairs - removed; records = records - removed; leftRooms = leftRooms - removed
                                _rooms.value = sortedRoomIds()
                            }
                            loadRooms(lockers, accountRoom, source.values)
                            previous = ids
                        }
                    }
            }

            com.latenighthack.social.runtime.keyedFlows(myProfiles.getProfileList(), { Unit }) { profileId ->
                kotlinx.coroutines.flow.flow<Unit> { watchInbox(lockers, profileId) }
            }.collect { }

        }
    }

    // --- room operations ---

    override suspend fun createGroup(name: String): RoomId = account.withAccount {
        runner.command { createGroupOwned(name) }
    }

    private suspend fun createGroupOwned(name: String): RoomId {
        val lockers = lockers ?: error("createGroup requires start(lockers) first")
        val me = primaryProfileId()

        val groupKey = Secp256r1KeyPair.generate()
        val roomId = RoomKeying.publicKeyed(groupKey.publicKey.encode())
        val stamped = RoomRecord(
            roomId = roomId.rawValue,
            kind = RoomKind.ROOM_KIND_GROUP,
            sharedPrivateKey = groupKey.privateKey.encode(),
            localProfileId = me.rawValue,
            updatedAtMillis = Clock.System.now().toEpochMilliseconds(),
        )

        val groupName = name
        val built = RoomInfo { replaceDisclosure { name { value = groupName } } }
        val info = built.copy { disclosures = built.disclosures.map {
            RoomInfoDisclosures.sign(groupKey, roomId, RoomInfo.DisclosurePayload.fromByteArray(it.content))
        } }
        adopt(lockers, stamped.copy(initialInfo = info.toByteArray()))
        repairMembership(lockers, records.getValue(roomId))
        return roomId
    }

    override suspend fun openRendezvous(peerProfileId: ProfileId): RoomId = account.withAccount {
        runner.command { openRendezvousOwned(peerProfileId) }
    }

    private suspend fun openRendezvousOwned(peerProfileId: ProfileId): RoomId {
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
        account.withAccount { RoomKeying.publicKeyed(deriveChildKey(parentRoomId, purpose, salt).publicKey.encode()) }

    override suspend fun openDerivedRoom(parentRoomId: RoomId, purpose: String, salt: ByteArray): RoomId = account.withAccount {
        runner.command { openDerivedRoomOwned(parentRoomId, purpose, salt) }
    }

    private suspend fun openDerivedRoomOwned(parentRoomId: RoomId, purpose: String, salt: ByteArray): RoomId {
        check(ownsKeys()) { "room keys belong to a different account" }
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
        check(ownsKeys()) { "room keys belong to a different account" }
        val record = records[parentRoomId] ?: error("not a member of the parent room")
        val seed = SHA256.digest(
            DERIVED_ROOM_DOMAIN + purpose.encodeToByteArray() + record.sharedPrivateKey + salt,
        )
        return Secp256r1KeyPair.fromPrivateKey(seed) ?: error("could not derive a child room key")
    }

    override suspend fun createInviteCode(roomId: RoomId): InviteCode = account.withAccount {
        runner.command { createInviteCodeOwned(roomId) }
    }

    private suspend fun createInviteCodeOwned(roomId: RoomId): InviteCode {
        check(ownsKeys()) { "room keys belong to a different account" }
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

    override suspend fun revokeInviteCode(roomId: RoomId, code: InviteCode): Unit = account.withAccount {
        runner.command { revokeInviteCodeOwned(roomId, code) }
    }

    private suspend fun revokeInviteCodeOwned(roomId: RoomId, code: InviteCode) {
        check(ownsKeys()) { "room keys belong to a different account" }
        val record = records[roomId] ?: return
        joinClient.revokeInviteCode(RevokeInviteCodeRequest {
            this.code = code
            groupPrivateKey = record.sharedPrivateKey
        })
    }

    override suspend fun inviteToRoom(roomId: RoomId, peerProfileId: ProfileId): Unit = account.withAccount {
        runner.command { inviteToRoomOwned(roomId, peerProfileId) }
    }

    private suspend fun inviteToRoomOwned(roomId: RoomId, peerProfileId: ProfileId) {
        check(ownsKeys()) { "room keys belong to a different account" }
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

    override suspend fun joinByCode(code: InviteCode): RoomId = account.withAccount {
        runner.command { joinByCodeOwned(code) }
    }

    private suspend fun joinByCodeOwned(code: InviteCode): RoomId {
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
        records[roomId]?.let { repairMembership(lockers, it); return roomId }
        adopt(lockers, RoomRecord(
            roomId = invite.roomId,
            kind = RoomKind.ROOM_KIND_GROUP,
            sharedPrivateKey = invite.groupPrivateKey,
            localProfileId = me.rawValue,
        ))
        writeMembership(lockers, roomId, me)
        return roomId
    }

    override suspend fun leave(roomId: RoomId): Unit = account.withAccount {
        runner.command { leaveOwned(roomId) }
    }

    private suspend fun leaveOwned(roomId: RoomId) {
        check(ownsKeys()) { "room keys belong to a different account" }
        val lockers = lockers ?: return
        val record = records[roomId] ?: return
        val leaving = record.copy(left = true, leaving = true)
        // Persist leave intent before any destructive room write; retain the encrypted key for repair.
        writeAccountRecord(lockers, leaving)
        stateMutex.withLock { records = records + (roomId to leaving); _rooms.value = sortedRoomIds() }
        repairLeave(lockers, leaving)
    }

    override suspend fun updateInfo(roomId: RoomId, builder: RoomInfoBuilder.() -> Unit): Unit = account.withAccount {
        runner.command { updateInfoOwned(roomId, builder) }
    }

    private suspend fun updateInfoOwned(roomId: RoomId, builder: RoomInfoBuilder.() -> Unit) {
        check(ownsKeys()) { "room keys belong to a different account" }
        val lockers = lockers ?: error("updateInfo requires start(lockers) first")
        val roomKey = keyPairs[roomId] ?: error("not a member of this room")
        writeInfo(lockers, roomId, roomKey, builder = builder)
    }

    override suspend fun markUpdated(roomId: RoomId): Unit = account.withAccount {
        runner.command { markUpdatedOwned(roomId) }
    }

    private suspend fun markUpdatedOwned(roomId: RoomId) {
        check(ownsKeys()) { "room keys belong to a different account" }
        val lockers = lockers ?: return
        val bumped = stateMutex.withLock {
            val record = records[roomId] ?: return
            record.copy(updatedAtMillis = Clock.System.now().toEpochMilliseconds()).also {
                records = records + (roomId to it)
                _rooms.value = sortedRoomIds()
            }
        }
        accountRoom()?.let { accountRoom ->
            writeAccountRecord(lockers, bumped)
        }
    }

    override fun watchRooms(): Flow<List<RoomId>> = combine(_rooms, account.owner, account.generation, loadedGeneration) { rooms, owner, epoch, loadedEpoch ->
        if (owner != null && owner == loadedOwner.value && epoch == loadedEpoch) rooms else emptyList()
    }

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
        if (ownsKeys()) records[roomId]?.let { ProfileId { rawValue = it.localProfileId } } else null

    override fun roomKind(roomId: RoomId): RoomKind? = if (ownsKeys()) records[roomId]?.kind else null

    // --- invite delivery + inbox ---

    private suspend fun sendInvite(lockers: LockersClient, recipient: ProfileId, invite: Invite) {
        val signed = myProfiles.sign(ProfileId(rawValue = invite.inviterProfileId), 6, invite.toByteArray())
            ?: error("inviter profile cannot sign")
        val envelope = Sealing.seal(recipient.rawValue, signed.toByteArray())
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
                    account.withAccount {
                        processInvite(lockers, profileId, envelope)
                        inboxClient(lockers).deleteLocker(inboxRoom, lockerId)
                    }
                    stateMutex.withLock {
                        processedInvites.add(lockerId)
                        if (processedInvites.size > 1024) processedInvites.remove(processedInvites.first())
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun withinInviteBounds(envelope: SealedEnvelope): Boolean =
        envelope.ciphertext.size <= 70_000 && envelope.wrappedKey.size <= 128 && envelope.ephemeralPublicKey.size == 33

    private suspend fun processInvite(lockers: LockersClient, profileId: ProfileId, envelope: SealedEnvelope) {
        if (!withinInviteBounds(envelope)) return
        val secret = myProfiles.deriveSharedSecret(profileId, envelope.ephemeralPublicKey) ?: return
        val payload = Sealing.unsealWith(secret, envelope) ?: return
        // The plaintext is attacker-chosen (anyone can seal to our inbox), so a malformed invite must
        // be skipped rather than allowed to crash this inbox collector.
        val signed = runCatching { com.latenighthack.social.common.v1.SignedContent.fromByteArray(payload) }.getOrNull() ?: return
        val invite = runCatching { Invite.fromByteArray(signed.content) }.getOrNull() ?: return
        val author = com.latenighthack.ktcrypto.Secp256r1PublicKey.decode(invite.inviterProfileId)
        if (!com.latenighthack.social.common.domain.verify(signed, 6, author)) return
        if (!invitePolicy.accept(profileId, ProfileId(rawValue = invite.inviterProfileId), invite.kind)) return
        when (invite.kind) {
            RoomKind.ROOM_KIND_RENDEZVOUS -> {
                val secretWithInviter = myProfiles.deriveSharedSecret(profileId, invite.inviterProfileId) ?: return
                val roomId = rendezvousRoomId(secretWithInviter)
                if (roomId in leftRooms || records.containsKey(roomId)) return
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
                if (roomId in leftRooms || records.containsKey(roomId)) return
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
    private suspend fun loadRooms(lockers: LockersClient, accountRoom: RoomId, sources: Collection<RoomRecord>) {
        val client = accountRoomsClient(lockers)
        // no ACK wait: offline, the cached room list must still load (reconnect reconciles the sub)
        client.subscribeToRoom(accountRoom, waitForSubscription = false)
        for (record in sources) {
            val id = RoomId(rawValue = record.roomId)
            if (record.left && !record.leaving) {
                stateMutex.withLock {
                    leftRooms = leftRooms + id
                    records = records - id
                    keyPairs = keyPairs - id
                    _rooms.value = sortedRoomIds()
                }
                continue
            }
            val raw = if (record.encryptedSharedPrivateKey.isNotEmpty()) {
                account.unprotectSecret("room/${record.roomId.toList()}", record.encryptedSharedPrivateKey)
            } else record.sharedPrivateKey
            val decrypted = record.copy(sharedPrivateKey = raw, encryptedSharedPrivateKey = ByteArray(0))
            remember(lockers, decrypted)
            if (record.encryptedSharedPrivateKey.isEmpty()) writeAccountRecord(lockers, decrypted)
            if (record.membershipPending || record.leaving) {
                CoroutineScope(kotlinx.coroutines.currentCoroutineContext()).launch {
                    recoverTask(mutableTaskHealth) {
                        account.withAccount { if (record.leaving) repairLeave(lockers, decrypted) else repairMembership(lockers, decrypted) }
                    }
                }
            }
        }
    }

    /**
     * Take on a room the user is now in: set its key material and record in memory, (re)subscribe,
     * and record it in the synced account-room list so a fresh restore recovers it. Key material is
     * set before any write so [RoomsKeySource] can sign for the room.
     */
    private suspend fun adopt(lockers: LockersClient, record: RoomRecord) {
        // Stamp the join/create time so a newly adopted room sorts to the front of the list.
        val stamped = record.copy(updatedAtMillis = Clock.System.now().toEpochMilliseconds(), membershipPending = true)
        stateMutex.withLock { leftRooms = leftRooms - RoomId(rawValue = stamped.roomId) }
        writeAccountRecord(lockers, stamped)
        remember(lockers, stamped)
    }

    /** Record the room in the synced account-room list so a fresh restore recovers it. */
    private suspend fun writeAccountRecord(lockers: LockersClient, stamped: RoomRecord) {
        account.requireOperationOwner()
        val commandOwner = account.owner.value
        val commandGeneration = account.generation.value
        val accountRoom = accountRoom() ?: error("account is not ready to persist room membership")
        val encrypted = if (stamped.left && !stamped.leaving) ByteArray(0)
            else account.protectSecret("room/${stamped.roomId.toList()}", stamped.sharedPrivateKey)
        account.requireOperationOwner()
        val protected = stamped.copy(sharedPrivateKey = ByteArray(0), encryptedSharedPrivateKey = encrypted)
        accountRoomsClient(lockers).updateLocker(
            accountRoom, LockerId(stamped.roomId, RoomsKeyspaces.ACCOUNT_ROOMS),
        ) { check(account.owner.value == commandOwner && account.generation.value == commandGeneration); protected }
    }

    /** Set in-memory key material + record and (re)subscribe. Idempotent; no account-room write. */
    private suspend fun remember(lockers: LockersClient, record: RoomRecord) {
        val roomId = RoomId(rawValue = record.roomId)
        val keyPair = Secp256r1KeyPair.fromPrivateKey(record.sharedPrivateKey) ?: return
        account.requireOperationOwner()
        stateMutex.withLock {
            keyPairs = keyPairs + (roomId to keyPair)
            records = records + (roomId to record)
            _rooms.value = sortedRoomIds()
        }
        infoClient(lockers).subscribeToRoom(roomId, waitForSubscription = false)
    }

    /** The user's room ids ordered by `updated_at`, newest first. */
    private fun sortedRoomIds(): List<RoomId> =
        records.entries.filter { !it.value.left && !it.value.membershipPending }.sortedByDescending { it.value.updatedAtMillis }.map { it.key }

    private fun accountRoom(): RoomId? =
        (account.lifecycle.value as? AccountManager.Lifecycle.Ready)?.privateRoom

    private suspend fun writeInfo(
        lockers: LockersClient,
        roomId: RoomId,
        roomKey: Secp256r1KeyPair,
        // A just-created room's info locker is known-empty; fresh=true skips the read round-trip.
        fresh: Boolean = false,
        builder: RoomInfoBuilder.() -> Unit,
    ) {
        val client = infoClient(lockers)
        com.latenighthack.social.runtime.rebasedUpdate(
            (if (fresh) null else client.getLocker(roomId, RoomsKeyspaces.ROOM_INFO_LOCKER)) ?: RoomInfo { },
            prepare = { current ->
                val built = current.copy(builder)
                val signed = built.disclosures.map {
                    RoomInfoDisclosures.sign(roomKey, roomId, RoomInfo.DisclosurePayload.fromByteArray(it.content))
                }
                built.copy { disclosures = signed }
            },
            commit = { transform -> client.updateLocker(roomId, RoomsKeyspaces.ROOM_INFO_LOCKER, builder = transform) },
        )
    }

    private suspend fun completeMembership(lockers: LockersClient, record: RoomRecord) {
        account.requireOperationOwner()
        val owner = account.owner.value
        val epoch = account.generation.value
        val room = accountRoom() ?: return
        val client = accountRoomsClient(lockers)
        val id = RoomId(rawValue = record.roomId)
        val lockerId = LockerId(record.roomId, RoomsKeyspaces.ACCOUNT_ROOMS)
        val completed = client.updateLocker(room, lockerId) { current ->
            check(account.owner.value == owner && account.generation.value == epoch)
            completeMembershipRecord(current)
        } ?: client.getLocker(room, lockerId) ?: return
        stateMutex.withLock {
            if (completed.left || records[id]?.left == true) {
                records = records - id; keyPairs = keyPairs - id; leftRooms = leftRooms + id
            } else records = records + (id to record.copy(membershipPending = false, initialInfo = ByteArray(0)))
            _rooms.value = sortedRoomIds()
        }
    }

    private suspend fun repairMembership(lockers: LockersClient, record: RoomRecord) = repairMutex.withLock {
        if (!record.membershipPending || record.left) return@withLock
        val id = RoomId(rawValue = record.roomId)
        if (stateMutex.withLock { records[id]?.left == true || id in leftRooms }) return@withLock
        val key = Secp256r1KeyPair.fromPrivateKey(record.sharedPrivateKey) ?: error("invalid membership key")
        infoClient(lockers).lockLocker(id, LockScope(kind = LockScopeKind.LOCK_SCOPE_ROOM), key,
            parentKeyPair = if (record.kind == RoomKind.ROOM_KIND_RENDEZVOUS) null else key)
        val changes = membershipChanges(ProfileId(rawValue = record.localProfileId))
        lockers.lockers.updateLockers(id, if (record.initialInfo.isEmpty()) changes else
            listOf(LockerClient.Change(RoomsKeyspaces.ROOM_INFO_LOCKER) { record.initialInfo }) + changes)
        completeMembership(lockers, record)
    }

    private val cleanupKeys = MutableStateFlow<Map<RoomId, Secp256r1KeyPair>>(emptyMap())
    private val repairMutex = Mutex()

    private suspend fun repairLeave(lockers: LockersClient, record: RoomRecord) = repairMutex.withLock {
        val id = RoomId(rawValue = record.roomId)
        val cleanupKey = Secp256r1KeyPair.fromPrivateKey(record.sharedPrivateKey) ?: return@withLock
        cleanupKeys.value = cleanupKeys.value + (id to cleanupKey)
        try {
        membershipClient(lockers).deleteLocker(id, LockerId(record.localProfileId, RoomsKeyspaces.MEMBERSHIP))
        memberProfileClient(lockers).deleteLocker(id, LockerId(record.localProfileId, RoomsKeyspaces.MEMBER_PROFILES))
        writeAccountRecord(lockers, record.copy(leaving = false, membershipPending = false, sharedPrivateKey = ByteArray(0)))
        stateMutex.withLock {
            leftRooms = leftRooms + id; records = records - id; keyPairs = keyPairs - id
            _rooms.value = sortedRoomIds()
        }
        } finally { cleanupKeys.value = cleanupKeys.value - id }
    }

    private fun membershipChanges(profileId: ProfileId): List<LockerClient.Change> {
        val now = Clock.System.now().toEpochMilliseconds()
        return listOf(
            LockerClient.Change(LockerId(profileId.rawValue, RoomsKeyspaces.MEMBERSHIP)) { Member(joinedAtMillis = now).toByteArray() },
            LockerClient.Change(LockerId(profileId.rawValue, RoomsKeyspaces.MEMBER_PROFILES)) { MemberProfile(profileId = profileId.rawValue).toByteArray() },
        )
    }

    private suspend fun writeMembership(lockers: LockersClient, roomId: RoomId, profileId: ProfileId) {
        lockers.lockers.updateLockers(roomId, membershipChanges(profileId))
        records[roomId]?.takeIf { it.membershipPending && !it.left }?.let { completeMembership(lockers, it) }
    }

    private suspend fun primaryProfileId(): ProfileId {
        val owner = checkNotNull(account.owner.value) { "account is signed out" }
        loadedOwner.first { it == owner }
        return myProfiles.getProfileList().first().firstOrNull() ?: error("a profile is required to use rooms")
    }

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
