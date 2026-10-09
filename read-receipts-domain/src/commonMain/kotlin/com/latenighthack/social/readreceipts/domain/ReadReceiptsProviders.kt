package com.latenighthack.social.readreceipts.domain

import com.latenighthack.social.messages.domain.MessagesManager
import com.latenighthack.social.rooms.domain.RoomsManager
import com.latenighthack.social.runtime.DomainLifecycle
import com.latenighthack.social.runtime.SocialScope
import me.tatarka.inject.annotations.IntoSet
import me.tatarka.inject.annotations.Provides

/**
 * kotlin-inject bindings for the read-receipts feature. Requires RoomsProviders and MessagesProviders
 * in the component (the rooms and messages managers are dependencies).
 */
interface ReadReceiptsProviders : com.latenighthack.social.runtime.SocialRuntimeProviders {
    @Provides
    @SocialScope
    fun readReceiptsManagerImpl(rooms: RoomsManager, messages: MessagesManager, myProfiles: com.latenighthack.social.profiles.domain.MyProfilesManager, tasks: com.latenighthack.social.runtime.SocialTaskScope, session: com.latenighthack.social.runtime.AccountSession): ReadReceiptsManagerImpl =
        ReadReceiptsManagerImpl(rooms, messages, myProfiles, scope = tasks.scope, session = session)

    @Provides
    fun readReceiptsManager(impl: ReadReceiptsManagerImpl): ReadReceiptsManager = impl

    @Provides
    @IntoSet
    fun readReceiptsLifecycle(impl: ReadReceiptsManagerImpl): DomainLifecycle = impl
}
