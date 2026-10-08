package com.latenighthack.social.messages.domain

import com.latenighthack.ktstore.Database
import com.latenighthack.social.profiles.domain.MyProfilesManager
import com.latenighthack.social.rooms.domain.RoomsManager
import com.latenighthack.social.runtime.AccountSession
import com.latenighthack.social.runtime.DomainLifecycle
import com.latenighthack.social.runtime.SocialScope
import me.tatarka.inject.annotations.IntoSet
import me.tatarka.inject.annotations.Provides

/**
 * kotlin-inject bindings for the messages feature. Requires RoomsProviders and ProfilesProviders in
 * the component (the rooms and profiles managers are dependencies). The app must provide the
 * [Database] the observed-messages cache is created from.
 */
interface MessagesProviders : com.latenighthack.social.runtime.SocialRuntimeProviders {
    @Provides
    @SocialScope
    fun messagesManagerImpl(
        rooms: RoomsManager,
        myProfiles: MyProfilesManager,
        database: Database,
        session: AccountSession, tasks: com.latenighthack.social.runtime.SocialTaskScope): MessagesManagerImpl = MessagesManagerImpl(rooms, myProfiles, database, session = session, scope = tasks.scope)

    @Provides
    fun messagesManager(impl: MessagesManagerImpl): MessagesManager = impl

    @Provides
    @IntoSet
    fun messagesLifecycle(impl: MessagesManagerImpl): DomainLifecycle = impl

    @Provides
    @SocialScope
    fun draftsManagerImpl(database: Database, session: AccountSession, tasks: com.latenighthack.social.runtime.SocialTaskScope): DraftsManagerImpl = DraftsManagerImpl(database, session = session, scope = tasks.scope)

    @Provides
    fun draftsManager(impl: DraftsManagerImpl): DraftsManager = impl

    @Provides
    @IntoSet
    fun draftsLifecycle(impl: DraftsManagerImpl): DomainLifecycle = impl
}
