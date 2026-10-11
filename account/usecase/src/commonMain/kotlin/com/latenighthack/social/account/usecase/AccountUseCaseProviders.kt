package com.latenighthack.social.account.usecase

import com.latenighthack.social.account.domain.AccountManager
import me.tatarka.inject.annotations.Provides

/** kotlin-inject bindings for the account use cases. Requires AccountProviders in the component. */
interface AccountUseCaseProviders {
    @Provides
    fun createAccountUseCase(manager: AccountManager): CreateAccountUseCase =
        CreateAccountUseCase(manager)

    @Provides
    fun signOutUseCase(manager: AccountManager): SignOutUseCase = SignOutUseCase(manager)
}
