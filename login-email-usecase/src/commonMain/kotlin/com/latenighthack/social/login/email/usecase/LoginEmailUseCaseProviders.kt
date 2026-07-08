package com.latenighthack.social.login.email.usecase

import com.latenighthack.social.account.domain.AccountManager
import com.latenighthack.social.login.core.domain.LoginClient
import me.tatarka.inject.annotations.Provides

/**
 * kotlin-inject bindings for the email magic-link use cases. Requires LoginCoreProviders (for
 * [LoginClient]) and AccountProviders (for [AccountManager]).
 */
interface LoginEmailUseCaseProviders {
    @Provides
    fun requestEmailLinkUseCase(loginClient: LoginClient): RequestEmailLinkUseCase =
        RequestEmailLinkUseCase(loginClient)

    @Provides
    fun completeEmailLinkUseCase(loginClient: LoginClient, account: AccountManager): CompleteEmailLinkUseCase =
        CompleteEmailLinkUseCase(loginClient, account)
}
