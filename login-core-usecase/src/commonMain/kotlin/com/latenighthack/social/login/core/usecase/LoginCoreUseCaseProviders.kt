package com.latenighthack.social.login.core.usecase

import com.latenighthack.social.account.domain.AccountManager
import com.latenighthack.social.login.core.domain.LoginClient
import me.tatarka.inject.annotations.Provides

/**
 * kotlin-inject binding for the shared login use case. Requires LoginCoreProviders (for [LoginClient])
 * and AccountProviders (for [AccountManager]). Each provider use-case module adds its own bindings.
 */
interface LoginCoreUseCaseProviders {
    @Provides
    fun bindCurrentAccountUseCase(loginClient: LoginClient, account: AccountManager): BindCurrentAccountUseCase =
        BindCurrentAccountUseCase(loginClient, account)
}
