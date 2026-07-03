package com.latenighthack.social.messages.usecase

import com.latenighthack.social.messages.domain.DraftsManager
import com.latenighthack.social.messages.domain.MessagesManager
import me.tatarka.inject.annotations.Provides

/** kotlin-inject bindings for the messages use cases. Requires MessagesProviders in the component. */
interface MessagesUseCaseProviders {
    @Provides
    fun sendMessageUseCase(messages: MessagesManager, drafts: DraftsManager): SendMessageUseCase =
        SendMessageUseCase(messages, drafts)

    @Provides
    fun watchMessagesUseCase(messages: MessagesManager): WatchMessagesUseCase = WatchMessagesUseCase(messages)

    @Provides
    fun saveDraftUseCase(drafts: DraftsManager): SaveDraftUseCase = SaveDraftUseCase(drafts)

    @Provides
    fun watchDraftUseCase(drafts: DraftsManager): WatchDraftUseCase = WatchDraftUseCase(drafts)
}
