package com.latenighthack.social.contacts.usecase

import com.latenighthack.social.contacts.domain.ContactsManager
import com.latenighthack.social.profiles.v1.ProfileId

/** Blocks [profileId]. */
class BlockContactUseCase(
    private val contacts: ContactsManager,
) {
    suspend fun block(profileId: ProfileId) = contacts.block(profileId)
}
