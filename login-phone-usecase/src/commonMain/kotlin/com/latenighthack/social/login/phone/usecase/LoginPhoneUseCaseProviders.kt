package com.latenighthack.social.login.phone.usecase

import com.latenighthack.social.account.domain.AccountManager
import com.latenighthack.social.login.core.domain.LoginClient
import me.tatarka.inject.annotations.Provides

/**
 * kotlin-inject bindings for the phone OTP use cases. Requires LoginCoreProviders (for [LoginClient])
 * and AccountProviders (for [AccountManager]).
 */
interface LoginPhoneUseCaseProviders {
    @Provides
    fun requestPhoneCodeUseCase(loginClient: LoginClient): RequestPhoneCodeUseCase =
        RequestPhoneCodeUseCase(loginClient)

    @Provides
    fun verifyPhoneCodeUseCase(loginClient: LoginClient, account: AccountManager): VerifyPhoneCodeUseCase =
        VerifyPhoneCodeUseCase(loginClient, account)
}
