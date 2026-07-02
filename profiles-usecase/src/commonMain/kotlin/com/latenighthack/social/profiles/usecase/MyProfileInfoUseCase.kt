package com.latenighthack.social.profiles.usecase

import com.latenighthack.social.profiles.domain.MyProfilesManager
import com.latenighthack.social.profiles.domain.displayName
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Watches the display name of the user's profile. Mono-profile for now: the first profile is the
 * user's profile; emits null until one exists.
 */
class MyProfileInfoUseCase(
    private val myProfiles: MyProfilesManager,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun watch(): Flow<String?> =
        myProfiles.getProfileList().flatMapLatest { ids ->
            ids.firstOrNull()
                ?.let { id -> myProfiles.watchProfile(id).map { it?.displayName() } }
                ?: flowOf(null)
        }
}
