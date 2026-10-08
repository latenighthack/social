package com.latenighthack.social.typing.domain

import com.latenighthack.social.rooms.domain.RoomsManager
import com.latenighthack.social.runtime.DomainLifecycle
import com.latenighthack.social.runtime.SocialScope
import me.tatarka.inject.annotations.IntoSet
import me.tatarka.inject.annotations.Provides

/**
 * kotlin-inject bindings for the typing feature. Requires RoomsProviders in the component (the rooms
 * manager is a dependency).
 */
interface TypingProviders : com.latenighthack.social.runtime.SocialRuntimeProviders {
    @Provides
    @SocialScope
    fun typingManagerImpl(rooms: RoomsManager, tasks: com.latenighthack.social.runtime.SocialTaskScope): TypingManagerImpl = TypingManagerImpl(rooms, scope = tasks.scope)

    @Provides
    fun typingManager(impl: TypingManagerImpl): TypingManager = impl

    @Provides
    @IntoSet
    fun typingLifecycle(impl: TypingManagerImpl): DomainLifecycle = impl
}
