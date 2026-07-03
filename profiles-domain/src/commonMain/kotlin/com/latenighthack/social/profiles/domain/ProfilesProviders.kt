package com.latenighthack.social.profiles.domain

import com.latenighthack.ktstore.StoreDelegate
import com.latenighthack.social.account.domain.AccountKeySource
import com.latenighthack.social.account.domain.AccountManager
import com.latenighthack.social.runtime.DomainLifecycle
import com.latenighthack.social.runtime.SocialScope
import me.tatarka.inject.annotations.IntoSet
import me.tatarka.inject.annotations.Provides

/**
 * kotlin-inject bindings for the profiles feature. Requires AccountProviders in the component (the
 * account manager is a dependency and the account key source is the profile chain's fallback). The
 * app must provide the [StoreDelegate] the observed-profiles cache is created from.
 */
interface ProfilesProviders {
    @Provides
    @SocialScope
    fun myProfilesManagerImpl(account: AccountManager): MyProfilesManagerImpl =
        MyProfilesManagerImpl(account)

    @Provides
    fun myProfilesManager(impl: MyProfilesManagerImpl): MyProfilesManager = impl

    @Provides
    @SocialScope
    fun profilesManagerImpl(delegate: StoreDelegate): ProfilesManagerImpl =
        ProfilesManagerImpl(delegate)

    @Provides
    fun profilesManager(impl: ProfilesManagerImpl): ProfilesManager = impl

    @Provides
    @SocialScope
    fun profileKeySource(myProfiles: MyProfilesManagerImpl, fallback: AccountKeySource): ProfileKeySource =
        ProfileKeySource(myProfiles, fallback)

    @Provides
    @IntoSet
    fun myProfilesLifecycle(impl: MyProfilesManagerImpl): DomainLifecycle = impl

    @Provides
    @IntoSet
    fun profilesLifecycle(impl: ProfilesManagerImpl): DomainLifecycle = impl
}
