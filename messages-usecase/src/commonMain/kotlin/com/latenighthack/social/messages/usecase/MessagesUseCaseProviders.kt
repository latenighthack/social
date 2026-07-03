package com.latenighthack.social.messages.usecase

import com.latenighthack.social.messages.domain.MessagesManager
import me.tatarka.inject.annotations.Provides

/** kotlin-inject bindings for the messages use cases. Requires MessagesProviders in the component. */
interface MessagesUseCaseProviders {
    @Provides
    fun sendMessageUseCase(messages: MessagesManager): SendMessageUseCase = SendMessageUseCase(messages)

    @Provides
    fun watchMessagesUseCase(messages: MessagesManager): WatchMessagesUseCase = WatchMessagesUseCase(messages)
}
