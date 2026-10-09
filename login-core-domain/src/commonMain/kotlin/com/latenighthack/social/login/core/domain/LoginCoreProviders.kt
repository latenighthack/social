package com.latenighthack.social.login.core.domain

import com.latenighthack.ktbuf.net.RpcClient
import com.latenighthack.social.observability.*
import me.tatarka.inject.annotations.IntoSet
import me.tatarka.inject.annotations.Provides

/**
 * kotlin-inject binding for the shared login transport. Requires an `RpcClient` binding (shared with
 * remote-content and rooms). Provider use-case modules bind their own use cases; they all resolve
 * this one [LoginClient].
 */
interface LoginCoreProviders : SocialTelemetryProviders {
    @Provides @IntoSet
    fun loginObservabilityFeature(): SocialFeatureDescriptor = SocialFeatureDescriptor("login")
    @Provides
    fun loginClient(rpcClient: RpcClient): LoginClient = LoginClientImpl(rpcClient).observedBy(socialTelemetry())
}
