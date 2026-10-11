package com.latenighthack.social.profiles.usecase

import com.latenighthack.social.profiles.domain.MyProfilesManager
import kotlinx.coroutines.flow.first

/**
 * Whether the user has at least one profile. Suspends until the initial profile load has completed
 * so a freshly started session doesn't report "no profile" before its profiles have been read.
 * Rooms require a profile, so callers use this to decide whether onboarding is still needed.
 */
class HasProfileUseCase(
    private val myProfiles: MyProfilesManager,
) {
    suspend fun check(): Boolean {
        myProfiles.isLoaded.first { it }
        return myProfiles.getProfileList().first().isNotEmpty()
    }
}
