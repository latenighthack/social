package com.latenighthack.social.contacts.usecase

import com.latenighthack.social.contacts.domain.Contact
import com.latenighthack.social.contacts.domain.ContactsManager
import kotlinx.coroutines.flow.Flow

/** Watches the user's contacts, each carrying its friend and/or block state. */
class WatchContactsUseCase(
    private val contacts: ContactsManager,
) {
    fun watch(): Flow<List<Contact>> = contacts.watchContacts()
}
