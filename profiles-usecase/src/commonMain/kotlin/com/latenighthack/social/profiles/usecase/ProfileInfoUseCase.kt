package com.latenighthack.social.profiles.usecase

import com.latenighthack.social.profiles.domain.ProfilesManager
import com.latenighthack.social.profiles.domain.displayName
import com.latenighthack.social.profiles.v1.ProfileId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Watches the display name of an observed profile. */
class ProfileInfoUseCase(
    private val profiles: ProfilesManager,
) {
    fun watch(profileId: ProfileId): Flow<String?> =
        profiles.watchProfile(profileId).map { it?.displayName() }
}
