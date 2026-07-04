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
interface ReadReceiptsProviders {
    @Provides
    @SocialScope
    fun readReceiptsManagerImpl(rooms: RoomsManager, messages: MessagesManager): ReadReceiptsManagerImpl =
        ReadReceiptsManagerImpl(rooms, messages)

    @Provides
    fun readReceiptsManager(impl: ReadReceiptsManagerImpl): ReadReceiptsManager = impl

    @Provides
    @IntoSet
    fun readReceiptsLifecycle(impl: ReadReceiptsManagerImpl): DomainLifecycle = impl
}
