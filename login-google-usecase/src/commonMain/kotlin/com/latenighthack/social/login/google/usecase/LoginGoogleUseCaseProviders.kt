package com.latenighthack.social.login.google.usecase

import com.latenighthack.social.account.domain.AccountManager
import com.latenighthack.social.login.core.domain.LoginClient
import com.latenighthack.social.login.google.domain.GoogleSignInClient
import me.tatarka.inject.annotations.Provides

/**
 * kotlin-inject binding for the Google sign-in use case. Requires LoginCoreProviders (for [LoginClient]),
 * AccountProviders (for [AccountManager]), and the app-supplied platform [GoogleSignInClient] binding.
 */
interface LoginGoogleUseCaseProviders {
    @Provides
    fun authenticateWithGoogleUseCase(
        loginClient: LoginClient,
        googleSignIn: GoogleSignInClient,
        account: AccountManager,
    ): AuthenticateWithGoogleUseCase = AuthenticateWithGoogleUseCase(loginClient, googleSignIn, account)
}
