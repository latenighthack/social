package com.latenighthack.social.profiles.domain

import com.latenighthack.ktcrypto.Secp256r1KeyPair
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
import com.latenighthack.social.profiles.v1.Profile
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
) : MyProfilesManager {

    // In-memory profile keys (immutable-swap for consistent reads from writeKey).
    private var keyPairs: Map<ProfileId, Secp256r1KeyPair> = emptyMap()

    private val _profiles = MutableStateFlow<Map<ProfileId, Profile>>(emptyMap())

    private var job: Job? = null
    private var loaded = false
    private var lockers: LockersClient? = null

    override fun start(lockers: LockersClient) {
        this.lockers = lockers
        if (job?.isActive == true) return
        loaded = false
        job = scope.launch { run() }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    override fun getProfileList(): Flow<List<ProfileId>> =
        _profiles.map { it.keys.toList() }.distinctUntilChanged()

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
            if (lifecycle is AccountManager.Lifecycle.Ready && !loaded) {
                loadProfiles(lifecycle.privateRoom)
                loaded = true
            }
        }
    }

    private suspend fun loadProfiles(accountRoom: RoomId) {
        val lockers = lockers ?: return
        val sourceClient = sourceClient(lockers)
        val profileClient = profileClient(lockers)
        sourceClient.subscribeToRoom(accountRoom)

        for ((_, source) in sourceClient.getAllLockers(accountRoom)) {
            val profileId = source.profileId ?: continue
            val keyPair = Secp256r1KeyPair.fromPrivateKey(source.privateKey) ?: continue
            keyPairs = keyPairs + (profileId to keyPair)
            ensureProfileRoom(profileClient, profileId, keyPair)
            profileClient.getLocker(profileId.toRoomId(), profileId.toProfileLockerId())?.let {
                _profiles.value = _profiles.value + (profileId to it)
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
        val disclosure = Disclosures.signDisplayName(keyPair, displayName)
        val profile = profileClient.updateLocker(profileId.toRoomId(), profileId.toProfileLockerId()) {
            it.copy { disclosures = listOf(disclosure) }
        } ?: Profile { disclosures = listOf(disclosure) }
        _profiles.value = _profiles.value + (profileId to profile)

        return profileId
    }

    override suspend fun setDisplayName(profileId: ProfileId, name: String) {
        val lockers = lockers ?: error("setDisplayName requires start(lockers) first")
        val keyPair = keyPairs[profileId] ?: error("unknown profile")
        val disclosure = Disclosures.signDisplayName(keyPair, name)
        val profile = profileClient(lockers).updateLocker(profileId.toRoomId(), profileId.toProfileLockerId()) {
            it.copy { disclosures = listOf(disclosure) }
        } ?: return
        _profiles.value = _profiles.value + (profileId to profile)
    }

    private suspend fun ensureProfileRoom(
        profileClient: TypedLockerClient<Profile>,
        profileId: ProfileId,
        keyPair: Secp256r1KeyPair,
    ) {
        val roomId = profileId.toRoomId()
        profileClient.subscribeToRoom(roomId)
        // Lock the whole profile room to the profile key (self-signed root lock; a no-op if
        // already locked). Mirrors AccountManagerImpl.initializePrivateRoom.
        profileClient.lockLocker(
            roomId,
            LockScope(kind = LockScopeKind.LOCK_SCOPE_ROOM),
            keyPair,
            parentKeyPair = keyPair,
        )
    }

    private fun sourceClient(lockers: LockersClient): TypedLockerClient<ProfileSource> =
        lockers.typed(ProfileKeyspaces.PROFILE_SOURCE, ProfileSource::toByteArray, ProfileSource.Companion::fromByteArray)

    private fun profileClient(lockers: LockersClient): TypedLockerClient<Profile> =
        lockers.typed(ProfileKeyspaces.PROFILE, Profile::toByteArray, Profile.Companion::fromByteArray)
}
