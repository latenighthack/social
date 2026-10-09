// Manager recovery / untrusted input boundaries catch transport-specific failures; cancellation escapes.
@file:Suppress("TooGenericExceptionCaught")

package com.latenighthack.social.profiles.domain

import com.latenighthack.social.observability.*

import kotlinx.coroutines.flow.asStateFlow

import com.latenighthack.ktcrypto.Secp256r1PublicKey
import com.latenighthack.ktcrypto.decode
import com.latenighthack.ktstore.Database
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
import com.latenighthack.social.runtime.TaskHealth
import com.latenighthack.social.runtime.recoverTask
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

/**
 * Observes profile lockers and mirrors them into memory + a persistent [ProfileStore]. Profiles
 * are loaded from the cache proactively at start and re-subscribed; live updates are parsed and
 * stored verbatim (verification deferred).
 */
class ProfilesManagerImpl(
    private val database: Database,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : ProfilesManager, DomainLifecycle, SocialTelemetryOwner {
    override var socialTelemetry: SocialTelemetry = NoopSocialTelemetry

    override suspend fun prepare() = socialTelemetry.measure("profiles", "prepare") { (run observedOperation@ {
        store.prepare()

        }) }
    override fun start(lockers: LockersClient) = run { socialTelemetry.event("profiles", "start"); (run observedOperation@ {
        runner.start(lockers) {
             recoverTask(mutableTaskHealth) {
            if (ready.isCancelled) ready = CompletableDeferred()
            try {
            val client = profileClient(lockers)
            val cached = store.getAllProfiles()
            _profiles.value = buildMap {
                cached.forEach { local -> local.profileId?.let { put(it, local.profile ?: Profile { }) } }
            }
            if (!ready.isCompleted) ready.complete(Unit)

            // Collect before (re)subscribing so the initial burst isn't missed. No ACK wait:
            // offline, one unacked subscribe must not stall resubscription of the rest.
            launch { client.allUpdates.collect { onUpdate(it) } }
            cached.forEach { local ->
                local.profileId?.let { client.subscribeToRoom(it.toRoomId(), waitForSubscription = false) }
            }
            } catch (failure: Exception) {
                if (!ready.isCompleted) ready.completeExceptionally(failure)
                throw failure
            }
        } }

        }) }
    override fun stop() = run { socialTelemetry.event("profiles", "stop"); (run observedOperation@ {
        runner.stop()

        }) }
    override suspend fun observe(profileId: ProfileId): Unit = socialTelemetry.measure("profiles", "observe") { (runner.command { observeOwned(profileId) }) }
    override fun getProfile(id: ProfileId): Profile?  = run { socialTelemetry.event("profiles", "cache"); (_profiles.value[id]) }
    override fun watchProfile(id: ProfileId): Flow<Profile?>  = (_profiles.map { it[id] }.distinctUntilChanged()).socialObserved(socialTelemetry, "profiles")
    override fun watchProfiles(ids: List<ProfileId>): Flow<List<Profile?>>  = (_profiles.map { current -> ids.map { current[it] } }.distinctUntilChanged()).socialObserved(socialTelemetry, "profiles")


    private val store = ProfileStore(database)

    private val _profiles = MutableStateFlow<Map<ProfileId, Profile>>(emptyMap())

    private val mutableTaskHealth = kotlinx.coroutines.flow.MutableStateFlow<TaskHealth>(TaskHealth.Idle)
    override val taskHealth = mutableTaskHealth.asStateFlow()
    private val runner = com.latenighthack.social.runtime.ManagerRunner(scope)
    private val lockers: LockersClient? get() = runner.token as? LockersClient
    // Completes once the cache has been loaded — gates all store access.
    private var ready = CompletableDeferred<Unit>()


    override suspend fun stopAndJoin() {
        runner.stopAndJoin()
    }


    private suspend fun observeOwned(profileId: ProfileId) {
        val lockers = lockers ?: error("observe requires start(lockers) first")
        val client = profileClient(lockers)
        client.subscribeToRoom(profileId.toRoomId(), waitForSubscription = false)
        // The initial snapshot is local; server synchronization continues independently.
        val cached = client.watch(profileId.toRoomId(), profileId.toProfileLockerId()).first()
        if (cached is TypedLockerUpdate.Present) ingest(profileId, cached.value)
    }


    override fun getProfiles(ids: List<ProfileId>): List<Profile?> =
        _profiles.value.let { current -> ids.map { current[it] } }


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
