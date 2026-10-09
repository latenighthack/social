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
interface TypingProviders : SocialTelemetryProviders {
    @Provides
    @IntoSet
    fun typingObservabilityFeature(): SocialFeatureDescriptor = SocialFeatureDescriptor("typing")

    @Provides
    @SocialScope
    fun typingManagerImpl(rooms: RoomsManager): TypingManagerImpl = TypingManagerImpl(rooms).observedBy(socialTelemetry())

    @Provides
    fun typingManager(impl: TypingManagerImpl): TypingManager = impl

    @Provides
    @IntoSet
    fun typingLifecycle(impl: TypingManagerImpl): DomainLifecycle = impl
}
