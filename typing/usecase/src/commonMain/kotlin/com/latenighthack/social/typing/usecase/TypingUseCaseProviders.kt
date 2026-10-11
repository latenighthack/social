package com.latenighthack.social.typing.usecase

import com.latenighthack.social.typing.domain.TypingManager
import me.tatarka.inject.annotations.Provides

/**
 * kotlin-inject bindings for the typing use cases. Requires TypingProviders in the component.
 */
interface TypingUseCaseProviders {
    @Provides
    fun setTypingUseCase(typing: TypingManager): SetTypingUseCase = SetTypingUseCase(typing)

    @Provides
    fun watchTypingUseCase(typing: TypingManager): WatchTypingUseCase = WatchTypingUseCase(typing)
}
