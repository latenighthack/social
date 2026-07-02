package com.latenighthack.social.profiles.domain

import com.latenighthack.lockers.connector.LockersClient
import com.latenighthack.social.profiles.v1.Profile
import com.latenighthack.social.profiles.v1.ProfileId
import kotlinx.coroutines.flow.Flow

/**
 * The user's own set of profiles. Each profile is a key pair whose secret half lives as a
 * locker in the account room; the profile is presented publicly as signed disclosures in the
 * profile's own key-locked room. All profiles are held in memory and loaded proactively once
 * the account is ready. Starts with just display name.
 */
interface MyProfilesManager {
    fun start(lockers: LockersClient)
    fun stop()

    /** Mints a new profile with the given display name and returns its id. */
    suspend fun createProfile(displayName: String): ProfileId

    /** Updates the display name of one of the user's profiles. */
    suspend fun setDisplayName(profileId: ProfileId, name: String)

    /** The ids of the user's profiles, updated as profiles are added. */
    fun getProfileList(): Flow<List<ProfileId>>

    fun getProfile(id: ProfileId): Profile?
    fun watchProfile(id: ProfileId): Flow<Profile?>
    fun getProfiles(ids: List<ProfileId>): List<Profile?>
    fun watchProfiles(ids: List<ProfileId>): Flow<List<Profile?>>
}
