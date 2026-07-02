package com.latenighthack.social.profiles.usecase

import com.latenighthack.social.profiles.domain.MyProfilesManager
import com.latenighthack.social.profiles.domain.replaceDisclosure
import kotlinx.coroutines.flow.first

/**
 * Sets the user's display name to [name]. Mono-profile for now: updates the first profile,
 * creating one if the user has none yet.
 */
class UpdateMyDisplayNameUseCase(
    private val myProfiles: MyProfilesManager,
) {
    suspend fun update(name: String) {
        val existing = myProfiles.getProfileList().first().firstOrNull()
        if (existing == null) {
            myProfiles.createProfile(name)
        } else {
            myProfiles.updateProfile(existing) {
                replaceDisclosure { displayName { value = name } }
            }
        }
    }
}
