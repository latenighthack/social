package com.latenighthack.social.debug.usecase

import com.latenighthack.social.debug.domain.DebugManager
import me.tatarka.inject.annotations.Provides

/** kotlin-inject bindings for the debug use cases. Requires DebugProviders in the component. */
interface DebugUseCaseProviders {
    @Provides
    fun watchLockersUseCase(debug: DebugManager): WatchLockersUseCase = WatchLockersUseCase(debug)
}
