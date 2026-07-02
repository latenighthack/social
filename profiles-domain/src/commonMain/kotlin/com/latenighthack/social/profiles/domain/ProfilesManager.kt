package com.latenighthack.social.profiles.domain

import com.latenighthack.lockers.connector.LockersClient
import com.latenighthack.social.profiles.v1.Profile
import com.latenighthack.social.profiles.v1.ProfileId
import kotlinx.coroutines.flow.Flow

/**
 * The set of profiles we are observing or have encountered from others. Listens to profile
 * lockers and keeps them in memory, backed by a persistent ktstore cache that is loaded
 * proactively at [start]. Ingested profiles are parsed and stored as-is (disclosure verification
 * is deferred).
 */
interface ProfilesManager {
    fun start(lockers: LockersClient)
    fun stop()

    /** Begin observing a profile: subscribes to its room so its updates flow into the cache. */
    suspend fun observe(profileId: ProfileId)

    fun getProfile(id: ProfileId): Profile?
    fun watchProfile(id: ProfileId): Flow<Profile?>
    fun getProfiles(ids: List<ProfileId>): List<Profile?>
    fun watchProfiles(ids: List<ProfileId>): Flow<List<Profile?>>
}
