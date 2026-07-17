package com.latenighthack.social.profiles.domain

import com.latenighthack.ktcrypto.Secp256r1PublicKey
import com.latenighthack.ktcrypto.decode
import com.latenighthack.ktstore.StoreDelegate
import com.latenighthack.lockers.common.RoomKeying
import com.latenighthack.lockers.connector.LockersClient
import com.latenighthack.lockers.connector.TypedLockerClient
import com.latenighthack.lockers.connector.TypedLockerUpdate
import com.latenighthack.social.runtime.DomainLifecycle
import com.latenighthack.social.profiles.v1.LocalProfile
import com.latenighthack.social.profiles.v1.Profile
import com.latenighthack.social.profiles.v1.ProfileId
import com.latenighthack.social.profiles.v1.copy
import com.latenighthack.social.profiles.v1.fromByteArray
import com.latenighthack.social.profiles.v1.toByteArray
import kotlinx.coroutines.CompletableDeferred
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
 * Observes profile lockers and mirrors them into memory + a persistent [ProfileStore]. Profiles
 * are loaded from the cache proactively at start and re-subscribed; live updates are parsed and
 * stored verbatim (verification deferred).
 */
class ProfilesManagerImpl(
    private val delegate: StoreDelegate,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : ProfilesManager, DomainLifecycle {

    private val store = ProfileStore(delegate)

    private val _profiles = MutableStateFlow<Map<ProfileId, Profile>>(emptyMap())

    private var job: Job? = null
    private var lockers: LockersClient? = null
    // Completes once the cache has been loaded — gates all store access.
    private val ready = CompletableDeferred<Unit>()

    override suspend fun prepare() {
        store.prepare()
    }

    override fun start(lockers: LockersClient) {
        this.lockers = lockers
        if (job?.isActive == true) return
        job = scope.launch {
            val client = profileClient(lockers)
            val cached = store.getAllProfiles()
            _profiles.value = buildMap {
                cached.forEach { local -> local.profileId?.let { put(it, local.profile ?: Profile { }) } }
            }
            if (!ready.isCompleted) ready.complete(Unit)

            // Collect before (re)subscribing so the initial burst isn't missed.
            launch { client.allUpdates.collect { onUpdate(it) } }
            cached.forEach { local -> local.profileId?.let { client.subscribeToRoom(it.toRoomId()) } }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    override suspend fun observe(profileId: ProfileId) {
        val lockers = lockers ?: error("observe requires start(lockers) first")
        val client = profileClient(lockers)
        client.subscribeToRoom(profileId.toRoomId())
        // Capture the current value directly so callers don't race the update stream.
        client.getLocker(profileId.toRoomId(), profileId.toProfileLockerId())?.let { ingest(profileId, it) }
    }

    override fun getProfile(id: ProfileId): Profile? = _profiles.value[id]

    override fun watchProfile(id: ProfileId): Flow<Profile?> =
        _profiles.map { it[id] }.distinctUntilChanged()

    override fun getProfiles(ids: List<ProfileId>): List<Profile?> =
        _profiles.value.let { current -> ids.map { current[it] } }

    override fun watchProfiles(ids: List<ProfileId>): Flow<List<Profile?>> =
        _profiles.map { current -> ids.map { current[it] } }.distinctUntilChanged()

    private suspend fun onUpdate(update: TypedLockerUpdate<Profile>) {
        val authority = RoomKeying.authorityKey(update.roomId) ?: return
        val profileId = ProfileId { rawValue = authority }
        when (update) {
            is TypedLockerUpdate.Present -> ingest(profileId, update.value)
            is TypedLockerUpdate.Deleted -> {
                ready.await()
                _profiles.value = _profiles.value - profileId
                store.removeProfile(profileId)
            }
        }
    }

    private suspend fun ingest(profileId: ProfileId, profile: Profile) {
        ready.await()
        val verified = verifyDisclosures(profileId, profile)
        _profiles.value = _profiles.value + (profileId to verified)
        store.saveProfile(LocalProfile {
            this.profileId = profileId
            this.profile = verified
        })
    }

    /** Keep only disclosures carrying a valid signature by the profile's own key (its id). */
    private suspend fun verifyDisclosures(profileId: ProfileId, profile: Profile): Profile {
        val key = Secp256r1PublicKey.decode(profileId.rawValue)
        val kept = profile.disclosures.filter { Disclosures.verify(it, key) }
        return profile.copy { disclosures = kept }
    }

    private fun profileClient(lockers: LockersClient): TypedLockerClient<Profile> =
        lockers.typed(ProfileKeyspaces.PROFILE, Profile::toByteArray, Profile.Companion::fromByteArray)
}
