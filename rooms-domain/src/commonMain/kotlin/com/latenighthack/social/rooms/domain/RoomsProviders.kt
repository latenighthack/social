package com.latenighthack.social.rooms.domain

import com.latenighthack.ktbuf.net.RpcClient
import com.latenighthack.social.account.domain.AccountManager
import com.latenighthack.social.profiles.domain.MyProfilesManager
import com.latenighthack.social.profiles.domain.ProfileKeySource
import com.latenighthack.social.runtime.DomainLifecycle
import com.latenighthack.social.runtime.SocialScope
import me.tatarka.inject.annotations.IntoSet
import me.tatarka.inject.annotations.Provides

/**
 * kotlin-inject bindings for the rooms feature. Requires AccountProviders and ProfilesProviders in
 * the component (the account and profiles managers are dependencies, and the profile key source is
 * the room chain's fallback), plus an `RpcClient` binding (shared with remote-content) for the Join
 * service. [RoomsKeySource] is the top of the lock-key chain; an app that includes rooms binds it as
 * the client's `LockKeySource`.
 */
interface RoomsProviders {
    @Provides
    @SocialScope
    fun joinClient(rpcClient: RpcClient): JoinClient = JoinClientImpl(rpcClient)

    @Provides
    @SocialScope
    fun roomsManagerImpl(
        account: AccountManager,
        myProfiles: MyProfilesManager,
        joinClient: JoinClient,
    ): RoomsManagerImpl = RoomsManagerImpl(account, myProfiles, joinClient)

    @Provides
    fun roomsManager(impl: RoomsManagerImpl): RoomsManager = impl

    @Provides
    @SocialScope
    fun roomsKeySource(rooms: RoomsManagerImpl, fallback: ProfileKeySource): RoomsKeySource =
        RoomsKeySource(rooms, fallback)

    @Provides
    @IntoSet
    fun roomsLifecycle(impl: RoomsManagerImpl): DomainLifecycle = impl
}
