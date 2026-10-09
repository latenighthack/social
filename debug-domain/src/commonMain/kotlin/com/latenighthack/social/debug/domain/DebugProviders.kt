package com.latenighthack.social.debug.domain

import com.latenighthack.social.observability.*

import com.latenighthack.social.runtime.DomainLifecycle
import com.latenighthack.social.runtime.SocialScope
import me.tatarka.inject.annotations.IntoSet
import me.tatarka.inject.annotations.Provides

/**
 * kotlin-inject bindings for the debug feature. The app must supply a [LockerCodecs] binding (it
 * knows every feature's proto types, so it builds the per-keyspace registry).
 */
interface DebugProviders : SocialTelemetryProviders {
    @Provides
    @IntoSet
    fun debugObservabilityFeature(): SocialFeatureDescriptor = SocialFeatureDescriptor("debug")

    @Provides
    @SocialScope
    fun debugManagerImpl(codecs: LockerCodecs): DebugManagerImpl = DebugManagerImpl(codecs).observedBy(socialTelemetry())

    @Provides
    fun debugManager(impl: DebugManagerImpl): DebugManager = impl

    @Provides
    @IntoSet
    fun debugLifecycle(impl: DebugManagerImpl): DomainLifecycle = impl
}
