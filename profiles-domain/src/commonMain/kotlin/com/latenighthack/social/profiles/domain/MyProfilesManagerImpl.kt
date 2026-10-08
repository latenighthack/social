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
import com.latenighthack.lockers.connector.TypedLockerUpdate
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.supervisorScope

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
    private val keyPairs = MutableStateFlow<Map<ProfileId, Secp256r1KeyPair>>(emptyMap())

    private val _profiles = MutableStateFlow<Map<ProfileId, Profile>>(emptyMap())
    private val _isLoaded = MutableStateFlow(false)
    private val loadedOwner = MutableStateFlow<String?>(null)
    private fun ownsKeys() = account.owner.value != null && account.owner.value == loadedOwner.value

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
        if (!ownsKeys()) return null
        val keyPair = keyPairs.value[profileId] ?: return null
        val peer = Secp256r1PublicKey.decode(peerPublicKey)
        val secret = Secp256r1.ECDH.sharedSecret(keyPair.privateKey, peer)
        return secret.takeIf { ownsKeys() }
    }

    override suspend fun sign(profileId: ProfileId, label: Long, content: ByteArray): SignedContent? =
        if (!ownsKeys()) null else keyPairs.value[profileId]?.let {
            signContent(it, label, content).takeIf { ownsKeys() }
        }

    override fun getProfileList(): Flow<List<ProfileId>> =
        combine(_profiles, account.owner, loadedOwner) { profiles, current, loaded ->
            if (current != null && current == loaded) profiles.keys.toList() else emptyList()
        }.distinctUntilChanged()

    override suspend fun hasProfileCached(): Boolean {
        val lockers = lockers ?: return false
        val accountRoom = account.localAccountRoom() ?: return false
        return lockers.getAllKnownLockers().any { update ->
            update.roomId.rawValue.contentEquals(accountRoom.rawValue) &&
                update.lockerId.keyspace?.value == ProfileKeyspaces.PROFILE_SOURCE.value
        }
    }

    override fun getProfile(id: ProfileId): Profile? = if (ownsKeys()) _profiles.value[id] else null

    override fun watchProfile(id: ProfileId): Flow<Profile?> =
        combine(_profiles, account.owner, loadedOwner) { profiles, current, loaded ->
            if (current != null && current == loaded) profiles[id] else null
        }.distinctUntilChanged()

    override fun getProfiles(ids: List<ProfileId>): List<Profile?> =
        ids.map { getProfile(it) }

    override fun watchProfiles(ids: List<ProfileId>): Flow<List<Profile?>> =
        combine(_profiles, account.owner, loadedOwner) { profiles, current, loaded ->
            ids.map { if (current != null && current == loaded) profiles[it] else null }
        }.distinctUntilChanged()

    /** The write key for a profile room whose authority matches one of our profiles. */
    internal fun writeKey(roomId: RoomId, lockerId: LockerId): Secp256r1KeyPair? {
        if (!ownsKeys()) return null
        val authority = RoomKeying.authorityKey(roomId) ?: return null
        return keyPairs.value[ProfileId { rawValue = authority }]
    }

    private suspend fun run() {
        account.lifecycle.map { it as? AccountManager.Lifecycle.Ready }
            .distinctUntilChanged { old, new ->
                    old?.accountId?.toList() == new?.accountId?.toList()
                }.collectLatest { ready ->
                val accountRoom = ready?.privateRoom
                keyPairs.value = emptyMap()
                _profiles.value = emptyMap()
                _isLoaded.value = false
                loadedOwner.value = ready?.accountId?.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
                if (accountRoom == null) return@collectLatest
                val client = lockers ?: return@collectLatest
                supervisorScope {
                    val observers = mutableMapOf<ProfileId, Job>()
                    var previous = emptySet<ProfileId>()
                    sourceClient(client).watchAll(accountRoom).collect { sources ->
                        val ids = sources.values.mapNotNull { it.profileId }.toSet()
                        val removed = previous - ids
                        removed.forEach { observers.remove(it)?.cancel() }
                        keyPairs.update { it - removed }
                        _profiles.update { it - removed }
                        loadProfiles(accountRoom, sources.values)
                        for (id in ids) if (id !in observers) {
                            observers[id] = launch {
                                profileClient(client).watch(id.toRoomId(), id.toProfileLockerId()).collect { value ->
                                    val profile = when (value) {
                                        is TypedLockerUpdate.Present -> value.value
                                        is TypedLockerUpdate.Deleted -> Profile { }
                                    }
                                    _profiles.update { it + (id to profile) }
                                }
                            }
                        }
                        previous = ids
                        _isLoaded.value = true
                    }
                }
            }
    }

    private suspend fun loadProfiles(accountRoom: RoomId, sources: Collection<ProfileSource>) {
        val lockers = lockers ?: return
        val sourceClient = sourceClient(lockers)
        val profileClient = profileClient(lockers)
        // no ACK wait: offline, cached profile sources must still load (reconnect reconciles the sub)
        sourceClient.subscribeToRoom(accountRoom, waitForSubscription = false)

        for (source in sources) {
            val profileId = source.profileId ?: continue
            val privateBytes = if (source.encryptedPrivateKey.isNotEmpty()) {
                account.unprotectSecret("profile/${profileId.rawValue.toList()}", source.encryptedPrivateKey)
            } else source.privateKey
            val keyPair = Secp256r1KeyPair.fromPrivateKey(privateBytes) ?: continue
            keyPairs.update { it + (profileId to keyPair) }
            profileClient.subscribeToRoom(profileId.toRoomId(), waitForSubscription = false)
            val cached = profileClient.watch(profileId.toRoomId(), profileId.toProfileLockerId()).first()
            if (cached is TypedLockerUpdate.Present) {
                _profiles.update { it + (profileId to cached.value) }
            }
            if (source.encryptedPrivateKey.isEmpty() && source.privateKey.isNotEmpty()) {
                CoroutineScope(currentCoroutineContext()).launch {
                    val encrypted = account.protectSecret("profile/${profileId.rawValue.toList()}", privateBytes)
                    sourceClient.updateLocker(accountRoom, profileId.toSourceLockerId()) {
                        it.copy { privateKey = ByteArray(0); encryptedPrivateKey = encrypted }
                    }
                }
            }
        }
    }

    override suspend fun createProfile(displayName: String): ProfileId {
        val lockers = lockers ?: error("createProfile requires start(lockers) first")
        val accountRoom = (account.lifecycle.value as? AccountManager.Lifecycle.Ready)?.privateRoom
            ?: error("account must be Ready to create a profile")

        val owner = account.owner.value
        loadedOwner.first { it == owner && it != null }
        val keyPair = Secp256r1KeyPair.generate()
        val publicKey = keyPair.publicKey.encode()
        val privateKeyBytes = keyPair.privateKey.encode()
        val profileId = ProfileId { rawValue = publicKey }
        keyPairs.update { it + (profileId to keyPair) }

        val encrypted = account.protectSecret("profile/${profileId.rawValue.toList()}", privateKeyBytes)
        // The account room lock authenticates writes; encryption provides confidentiality.
        val sourceClient = sourceClient(lockers)
        sourceClient.subscribeToRoom(accountRoom)
        sourceClient.updateLocker(accountRoom, profileId.toSourceLockerId()) {
            it.copy {
                this.profileId = profileId
                privateKey = ByteArray(0)
                encryptedPrivateKey = encrypted
            }
        }

        // Lock the profile's own room and write the initial signed disclosure.
        val profileClient = profileClient(lockers)
        ensureProfileRoom(profileClient, profileId, keyPair)
        val disclosure = Disclosures.sign(keyPair, profileId, displayNamePayload(displayName))
        val profile = profileClient.updateLocker(profileId.toRoomId(), profileId.toProfileLockerId()) {
            it.copy { disclosures = listOf(disclosure) }
        } ?: Profile { disclosures = listOf(disclosure) }
        check(account.owner.value == owner) { "account changed during profile creation" }
        _profiles.update { it + (profileId to profile) }

        return profileId
    }

    override suspend fun updateProfile(profileId: ProfileId, builder: ProfileBuilder.() -> Unit) {
        val lockers = lockers ?: error("updateProfile requires start(lockers) first")
        check(ownsKeys()) { "account is signed out" }
        val keyPair = keyPairs.value[profileId] ?: error("unknown profile")

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
        _profiles.update { it + (profileId to stored) }
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
