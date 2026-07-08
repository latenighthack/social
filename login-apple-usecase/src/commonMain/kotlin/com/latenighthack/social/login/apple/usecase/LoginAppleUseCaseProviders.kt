package com.latenighthack.social.login.apple.usecase

import com.latenighthack.social.account.domain.AccountManager
import com.latenighthack.social.login.apple.domain.AppleSignInClient
import com.latenighthack.social.login.core.domain.LoginClient
import me.tatarka.inject.annotations.Provides

/**
 * kotlin-inject binding for the Apple sign-in use case. Requires LoginCoreProviders (for [LoginClient]),
 * AccountProviders (for [AccountManager]), and the app-supplied platform [AppleSignInClient] binding.
 */
interface LoginAppleUseCaseProviders {
    @Provides
    fun authenticateWithAppleUseCase(
        loginClient: LoginClient,
        appleSignIn: AppleSignInClient,
        account: AccountManager,
    ): AuthenticateWithAppleUseCase = AuthenticateWithAppleUseCase(loginClient, appleSignIn, account)
}
