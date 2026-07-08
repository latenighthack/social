package com.latenighthack.social.login.usecase

import com.latenighthack.social.account.domain.AccountManager
import com.latenighthack.social.login.domain.AppleSignInClient
import com.latenighthack.social.login.domain.GoogleSignInClient
import com.latenighthack.social.login.domain.LoginClient
import me.tatarka.inject.annotations.Provides

/**
 * kotlin-inject bindings for the login use cases. Requires LoginProviders (for [LoginClient]) and
 * AccountProviders (for [AccountManager]) in the component, plus the app-supplied platform
 * [AppleSignInClient] / [GoogleSignInClient] bindings for the social use cases.
 */
interface LoginUseCaseProviders {
    @Provides
    fun authenticateWithAppleUseCase(
        loginClient: LoginClient,
        appleSignIn: AppleSignInClient,
        account: AccountManager,
    ): AuthenticateWithAppleUseCase = AuthenticateWithAppleUseCase(loginClient, appleSignIn, account)

    @Provides
    fun authenticateWithGoogleUseCase(
        loginClient: LoginClient,
        googleSignIn: GoogleSignInClient,
        account: AccountManager,
    ): AuthenticateWithGoogleUseCase = AuthenticateWithGoogleUseCase(loginClient, googleSignIn, account)

    @Provides
    fun requestEmailLinkUseCase(loginClient: LoginClient): RequestEmailLinkUseCase =
        RequestEmailLinkUseCase(loginClient)

    @Provides
    fun completeEmailLinkUseCase(loginClient: LoginClient, account: AccountManager): CompleteEmailLinkUseCase =
        CompleteEmailLinkUseCase(loginClient, account)

    @Provides
    fun requestPhoneCodeUseCase(loginClient: LoginClient): RequestPhoneCodeUseCase =
        RequestPhoneCodeUseCase(loginClient)

    @Provides
    fun verifyPhoneCodeUseCase(loginClient: LoginClient, account: AccountManager): VerifyPhoneCodeUseCase =
        VerifyPhoneCodeUseCase(loginClient, account)

    @Provides
    fun bindCurrentAccountUseCase(loginClient: LoginClient, account: AccountManager): BindCurrentAccountUseCase =
        BindCurrentAccountUseCase(loginClient, account)
}
