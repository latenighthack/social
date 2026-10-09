package com.latenighthack.social.typing.domain

import com.latenighthack.social.observability.*

import com.latenighthack.social.rooms.domain.RoomsManager
import com.latenighthack.social.runtime.DomainLifecycle
import com.latenighthack.social.runtime.SocialScope
import me.tatarka.inject.annotations.IntoSet
import me.tatarka.inject.annotations.Provides

/**
 * kotlin-inject bindings for the typing feature. Requires RoomsProviders in the component (the rooms
 * manager is a dependency).
 */
interface TypingProviders : com.latenighthack.social.runtime.SocialRuntimeProviders, SocialTelemetryProviders {
    @Provides
    @IntoSet
    fun typingObservabilityFeature(): SocialFeatureDescriptor = SocialFeatureDescriptor("typing")

    @Provides
    @SocialScope
    fun typingManagerImpl(rooms: RoomsManager, myProfiles: com.latenighthack.social.profiles.domain.MyProfilesManager, tasks: com.latenighthack.social.runtime.SocialTaskScope, session: com.latenighthack.social.runtime.AccountSession): TypingManagerImpl = TypingManagerImpl(rooms, myProfiles, scope = tasks.scope, session = session).observedBy(socialTelemetry())

    @Provides
    fun typingManager(impl: TypingManagerImpl): TypingManager = impl

    @Provides
    @IntoSet
    fun typingLifecycle(impl: TypingManagerImpl): DomainLifecycle = impl
}
