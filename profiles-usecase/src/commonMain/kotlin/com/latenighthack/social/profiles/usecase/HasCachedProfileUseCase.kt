package com.latenighthack.social.profiles.usecase

import com.latenighthack.social.profiles.domain.MyProfilesManager

/**
 * Whether the user already has a profile cached locally, without waiting for the session to
 * connect. Unlike [HasProfileUseCase] this never suspends on the initial load — it reads only the
 * on-disk locker cache — so the launch flow can route a returning user straight to home while the
 * connection and profile load settle in the background. Returns false when nothing is cached (a
 * new user, or a fresh install of a restored identity), in which case callers fall back to the
 * connected [HasProfileUseCase] path.
 */
class HasCachedProfileUseCase(
    private val myProfiles: MyProfilesManager,
) {
    suspend fun check(): Boolean = myProfiles.hasProfileCached()
}
