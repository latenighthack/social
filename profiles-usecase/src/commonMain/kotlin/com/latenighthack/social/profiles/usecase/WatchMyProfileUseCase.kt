package com.latenighthack.social.profiles.usecase

import com.latenighthack.social.profiles.domain.MyProfilesManager
import com.latenighthack.social.profiles.domain.avatar
import com.latenighthack.social.profiles.domain.displayName
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** The user's display name and avatar URL, either null until disclosed. */
data class MyProfile(val displayName: String?, val avatarUrl: String?)

/**
 * Watches the user's own profile — display name and avatar together. Mono-profile for now: the
 * first profile is the user's; emits null until one exists.
 */
class WatchMyProfileUseCase(
    private val myProfiles: MyProfilesManager,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun watch(): Flow<MyProfile?> =
        myProfiles.getProfileList().flatMapLatest { ids ->
            ids.firstOrNull()
                ?.let { id -> myProfiles.watchProfile(id).map { it?.let { MyProfile(it.displayName(), it.avatar()) } } }
                ?: flowOf(null)
        }
}
