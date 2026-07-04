package com.latenighthack.social.contacts.usecase

import com.latenighthack.social.contacts.domain.ContactsManager
import com.latenighthack.social.profiles.v1.ProfileId

/** Removes [profileId] from the user's friends. */
class UnfriendContactUseCase(
    private val contacts: ContactsManager,
) {
    suspend fun unfriend(profileId: ProfileId) = contacts.unfriend(profileId)
}
