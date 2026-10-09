package com.latenighthack.social.profiles.domain

import com.latenighthack.ktstore.*
import com.latenighthack.social.profiles.v1.LocalProfile
import com.latenighthack.social.profiles.v1.ProfileId
import com.latenighthack.social.profiles.v1.fromByteArray
import com.latenighthack.social.profiles.v1.toByteArray

/**
 * Device-local persistent cache of observed profiles, wrapped as [LocalProfile] (id + profile).
 * Keyed by the profile id's raw bytes.
 */
internal class ProfileStore(database: Database) : Store<LocalProfile>(database, ProfileStoreDefinitionV1) {
    private val profileIdKey = ProfileStoreDefinitionV1.profileIdKey

    suspend fun getAllProfiles(): List<LocalProfile> = getAll()

    suspend fun saveProfile(profile: LocalProfile) = save(profile)

    suspend fun removeProfile(profileId: ProfileId) = delete(profileIdKey.eq(profileId.toByteArray()))
}
