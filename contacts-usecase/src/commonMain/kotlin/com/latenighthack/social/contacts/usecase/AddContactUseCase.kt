package com.latenighthack.social.contacts.usecase

import com.latenighthack.social.contacts.domain.ContactsManager
import com.latenighthack.social.profiles.v1.ProfileId

/** Adds [profileId] to the user's friends. */
class AddContactUseCase(
    private val contacts: ContactsManager,
) {
    suspend fun add(profileId: ProfileId) = contacts.add(profileId)
}
