package com.latenighthack.social.account.domain

import com.latenighthack.ktstore.KeyValueStore
import com.latenighthack.lockers.connector.AuthenticationKeySource
import com.latenighthack.social.runtime.DomainLifecycle
import com.latenighthack.social.runtime.SocialScope
import me.tatarka.inject.annotations.IntoSet
import me.tatarka.inject.annotations.Provides

/**
 * kotlin-inject bindings for the account feature. A consuming component implements this interface
 * to obtain the account manager, its key source, and its lifecycle contribution. The app must
 * provide the [KeyValueStore] the manager persists its device-local identity in.
 */
interface AccountProviders {
    @Provides
    @SocialScope
    fun accountManagerImpl(keyValueStore: KeyValueStore): AccountManagerImpl =
        AccountManagerImpl(keyValueStore)

    @Provides
    fun accountManager(impl: AccountManagerImpl): AccountManager = impl

    @Provides
    @SocialScope
    fun accountKeySource(impl: AccountManagerImpl): AccountKeySource = AccountKeySource(impl)

    // The account key is always the session authenticator; the lock-side top of the chain is the
    // app's choice (it depends on which features are present), so it is not bound here.
    @Provides
    fun authenticationKeySource(keySource: AccountKeySource): AuthenticationKeySource = keySource

    @Provides
    @IntoSet
    fun accountLifecycle(impl: AccountManagerImpl): DomainLifecycle = impl
}
