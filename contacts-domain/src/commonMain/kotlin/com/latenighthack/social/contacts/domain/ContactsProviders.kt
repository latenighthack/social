package com.latenighthack.social.contacts.domain

import com.latenighthack.social.account.domain.AccountManager
import com.latenighthack.social.runtime.DomainLifecycle
import com.latenighthack.social.runtime.SocialScope
import me.tatarka.inject.annotations.IntoSet
import me.tatarka.inject.annotations.Provides

/**
 * kotlin-inject bindings for the contacts feature. Requires AccountProviders in the component (the
 * account manager is the only dependency; contacts write to the account room and need no key source
 * of their own).
 */
interface ContactsProviders {
    @Provides
    @SocialScope
    fun contactsManagerImpl(account: AccountManager): ContactsManagerImpl = ContactsManagerImpl(account)

    @Provides
    fun contactsManager(impl: ContactsManagerImpl): ContactsManager = impl

    @Provides
    @IntoSet
    fun contactsLifecycle(impl: ContactsManagerImpl): DomainLifecycle = impl
}
