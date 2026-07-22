package com.latenighthack.social.profiles.domain

import com.latenighthack.ktcrypto.ECDH
import com.latenighthack.ktcrypto.Secp256r1
import com.latenighthack.ktcrypto.Secp256r1KeyPair
import com.latenighthack.ktcrypto.Secp256r1PublicKey
import com.latenighthack.ktcrypto.decode
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
import com.latenighthack.social.account.domain.AccountManager
import com.latenighthack.social.common.domain.sign as signContent
import com.latenighthack.social.common.v1.SignedContent
import com.latenighthack.social.runtime.DomainLifecycle
import com.latenighthack.social.profiles.v1.Profile
import com.latenighthack.social.profiles.v1.ProfileBuilder
import com.latenighthack.social.profiles.v1.ProfileId
import com.latenighthack.social.profiles.v1.ProfileSource
import com.latenighthack.social.profiles.v1.copy
import com.latenighthack.social.profiles.v1.fromByteArray
import com.latenighthack.social.profiles.v1.toByteArray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Owns the user's profile key pairs (kept in memory, sourced from the account room) and drives
 * profile writes: it stores each `(id, private key)` as a [ProfileSource] locker in the account
 * room and writes signed [Profile] disclosures to each profile's own key-locked room. Its key
 * material is handed to the client through a [ProfileKeySource] wrapping this manager.
 */
class MyProfilesManagerImpl(
    private val account: AccountManager,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : MyProfilesManager, DomainLifecycle {

    // In-memory profile keys (immutable-swap for consistent reads from writeKey).
    private var keyPairs: Map<ProfileId, Secp256r1KeyPair> = emptyMap()

    private val _profiles = MutableStateFlow<Map<ProfileId, Profile>>(emptyMap())
    private val _isLoaded = MutableStateFlow(false)

    private var job: Job? = null
    private var lockers: LockersClient? = null

    override val isLoaded: StateFlow<Boolean> get() = _isLoaded

    override fun start(lockers: LockersClient) {
        this.lockers = lockers
        if (job?.isActive == true) return
        _isLoaded.value = false
        job = scope.launch { run() }
    }

    override fun stop() {
        job?.cancel()
        job = null
        _isLoaded.value = false
    }

    override suspend fun deriveSharedSecret(profileId: ProfileId, peerPublicKey: ByteArray): ByteArray? {
        val keyPair = keyPairs[profileId] ?: return null
        val peer = Secp256r1PublicKey.decode(peerPublicKey)
        return Secp256r1.ECDH.sharedSecret(keyPair.privateKey, peer)
    }

    override suspend fun sign(profileId: ProfileId, label: Long, content: ByteArray): SignedContent? =
        keyPairs[profileId]?.let { signContent(it, label, content) }

    override fun getProfileList(): Flow<List<ProfileId>> =
        _profiles.map { it.keys.toList() }.distinctUntilChanged()

    override suspend fun hasProfileCached(): Boolean {
        val lockers = lockers ?: return false
        val accountRoom = account.localAccountRoom() ?: return false
        return lockers.getAllKnownLockers().any { update ->
            update.roomId.rawValue.contentEquals(accountRoom.rawValue) &&
                update.lockerId.keyspace?.value == ProfileKeyspaces.PROFILE_SOURCE.value
        }
    }

    override fun getProfile(id: ProfileId): Profile? = _profiles.value[id]

    override fun watchProfile(id: ProfileId): Flow<Profile?> =
        _profiles.map { it[id] }.distinctUntilChanged()

    override fun getProfiles(ids: List<ProfileId>): List<Profile?> =
        _profiles.value.let { current -> ids.map { current[it] } }

    override fun watchProfiles(ids: List<ProfileId>): Flow<List<Profile?>> =
        _profiles.map { current -> ids.map { current[it] } }.distinctUntilChanged()

    /** The write key for a profile room whose authority matches one of our profiles. */
    internal fun writeKey(roomId: RoomId, lockerId: LockerId): Secp256r1KeyPair? {
        val authority = RoomKeying.authorityKey(roomId) ?: return null
        return keyPairs[ProfileId { rawValue = authority }]
    }

    private suspend fun run() {
        account.lifecycle.collect { lifecycle ->
            if (lifecycle is AccountManager.Lifecycle.Ready) {
                // Ready arrives offline too (cache-backed) and each reconnect re-emits it, so
                // reload on every emission: an offline cold-cache load legitimately sees nothing,
                // and the reconnect tick then picks up the server copy. loadProfiles is
                // idempotent; failures must not kill this collector.
                if (runCatching { loadProfiles(lifecycle.privateRoom) }.isSuccess) {
                    _isLoaded.value = true
                }
            }
        }
    }

    private suspend fun loadProfiles(accountRoom: RoomId) {
        val lockers = lockers ?: return
        val sourceClient = sourceClient(lockers)
        val profileClient = profileClient(lockers)
        // no ACK wait: offline, cached profile sources must still load (reconnect reconciles the sub)
        sourceClient.subscribeToRoom(accountRoom, waitForSubscription = false)

        for ((_, source) in sourceClient.getAllLockers(accountRoom)) {
            val profileId = source.profileId ?: continue
            val keyPair = Secp256r1KeyPair.fromPrivateKey(source.privateKey) ?: continue
            keyPairs = keyPairs + (profileId to keyPair)
            // Key material first, then best-effort server work: the room re-lock is a no-op for an
            // established profile and the profile read is cache-served, so an offline failure here
            // must not drop the key or sink the remaining profiles.
            runCatching { ensureProfileRoom(profileClient, profileId, keyPair) }
            runCatching {
                profileClient.getLocker(profileId.toRoomId(), profileId.toProfileLockerId())?.let {
                    _profiles.value = _profiles.value + (profileId to it)
                }
            }
        }
    }

    override suspend fun createProfile(displayName: String): ProfileId {
        val lockers = lockers ?: error("createProfile requires start(lockers) first")
        val accountRoom = (account.lifecycle.value as? AccountManager.Lifecycle.Ready)?.privateRoom
            ?: error("account must be Ready to create a profile")

        val keyPair = Secp256r1KeyPair.generate()
        val publicKey = keyPair.publicKey.encode()
        val privateKeyBytes = keyPair.privateKey.encode()
        val profileId = ProfileId { rawValue = publicKey }
        keyPairs = keyPairs + (profileId to keyPair)

        // Store the secret half in the account room (protected by the account room lock).
        val sourceClient = sourceClient(lockers)
        sourceClient.subscribeToRoom(accountRoom)
        sourceClient.updateLocker(accountRoom, profileId.toSourceLockerId()) {
            it.copy {
                this.profileId = profileId
                privateKey = privateKeyBytes
            }
        }

        // Lock the profile's own room and write the initial signed disclosure.
        val profileClient = profileClient(lockers)
        ensureProfileRoom(profileClient, profileId, keyPair)
        val disclosure = Disclosures.sign(keyPair, profileId, displayNamePayload(displayName))
        val profile = profileClient.updateLocker(profileId.toRoomId(), profileId.toProfileLockerId()) {
            it.copy { disclosures = listOf(disclosure) }
        } ?: Profile { disclosures = listOf(disclosure) }
        _profiles.value = _profiles.value + (profileId to profile)

        return profileId
    }

    override suspend fun updateProfile(profileId: ProfileId, builder: ProfileBuilder.() -> Unit) {
        val lockers = lockers ?: error("updateProfile requires start(lockers) first")
        val keyPair = keyPairs[profileId] ?: error("unknown profile")

        // Apply the caller's builder to the current profile, then re-sign every disclosure over
        // its payload so signatures always match the written content.
        val built = (getProfile(profileId) ?: Profile { }).copy(builder)
        val signed = mutableListOf<SignedContent>()
        for (disclosure in built.disclosures) {
            val payload = Profile.DisclosurePayload.fromByteArray(disclosure.content)
            signed.add(Disclosures.sign(keyPair, profileId, payload))
        }
        val updatedProfile = built.copy { disclosures = signed }

        val stored = profileClient(lockers)
            .updateLocker(profileId.toRoomId(), profileId.toProfileLockerId()) { updatedProfile }
            ?: updatedProfile
        _profiles.value = _profiles.value + (profileId to stored)
    }

    private suspend fun ensureProfileRoom(
        profileClient: TypedLockerClient<Profile>,
        profileId: ProfileId,
        keyPair: Secp256r1KeyPair,
    ) {
        val roomId = profileId.toRoomId()
        profileClient.subscribeToRoom(roomId)
        // Lock only the profile-content keyspace to the profile key (grant signed by the room
        // authority — the same profile key — so it is accepted on a public-keyed room without a
        // room-scope lock). Leaving the room otherwise open lets others drop sealed invites into
        // the unlocked inbox keyspace; a re-lock is a no-op.
        profileClient.lockLocker(
            roomId,
            LockScope(kind = LockScopeKind.LOCK_SCOPE_KEYSPACE, keyspace = ProfileKeyspaces.PROFILE),
            keyPair,
            parentKeyPair = keyPair,
        )
    }

    private fun displayNamePayload(name: String) = Profile.DisclosurePayload(
        content = Profile.DisclosurePayload.OneOfContent.displayName(
            Profile.DisclosurePayload.DisplayName(value = name),
        ),
    )

    private fun sourceClient(lockers: LockersClient): TypedLockerClient<ProfileSource> =
        lockers.typed(ProfileKeyspaces.PROFILE_SOURCE, ProfileSource::toByteArray, ProfileSource.Companion::fromByteArray)

    private fun profileClient(lockers: LockersClient): TypedLockerClient<Profile> =
        lockers.typed(ProfileKeyspaces.PROFILE, Profile::toByteArray, Profile.Companion::fromByteArray)
}
