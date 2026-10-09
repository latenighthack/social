package com.latenighthack.social.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import me.tatarka.inject.annotations.Provides

/** A single graph-owned parent; hosts can override the provider with their application scope. */
class SocialTaskScope(val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)) {
    fun cancel() = scope.cancel()
}

interface SocialRuntimeProviders {
    @Provides
    @SocialScope
    fun socialTaskScope(): SocialTaskScope = SocialTaskScope()
}
