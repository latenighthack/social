package com.latenighthack.social.contacts.usecase

import com.latenighthack.social.contacts.domain.ContactsManager
import me.tatarka.inject.annotations.Provides

/** kotlin-inject bindings for the contacts use cases. Requires ContactsProviders in the component. */
interface ContactsUseCaseProviders {
    @Provides
    fun addContactUseCase(contacts: ContactsManager): AddContactUseCase = AddContactUseCase(contacts)

    @Provides
    fun blockContactUseCase(contacts: ContactsManager): BlockContactUseCase = BlockContactUseCase(contacts)

    @Provides
    fun unfriendContactUseCase(contacts: ContactsManager): UnfriendContactUseCase = UnfriendContactUseCase(contacts)

    @Provides
    fun unblockContactUseCase(contacts: ContactsManager): UnblockContactUseCase = UnblockContactUseCase(contacts)

    @Provides
    fun watchContactsUseCase(contacts: ContactsManager): WatchContactsUseCase = WatchContactsUseCase(contacts)
}
