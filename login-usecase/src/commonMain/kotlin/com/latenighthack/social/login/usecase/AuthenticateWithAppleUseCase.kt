package com.latenighthack.social.login.usecase

import com.latenighthack.social.account.domain.AccountManager
import com.latenighthack.social.login.domain.AppleSignInClient
import com.latenighthack.social.login.domain.LoginClient
import com.latenighthack.social.login.v1.AuthenticateSocialRequest
import com.latenighthack.social.login.v1.Provider

/**
 * Signs in with Apple: acquire a native Apple id token, then either recover the bound account key or
 * report that binding is needed. On [SignInResult.NeedsBinding], the app creates or reuses an account
 * and calls [BindCurrentAccountUseCase].
 */
class AuthenticateWithAppleUseCase(
    private val loginClient: LoginClient,
    private val appleSignIn: AppleSignInClient,
    private val account: AccountManager,
) {
    suspend fun authenticate(): SignInResult {
        val idToken = try {
            appleSignIn.signIn()
        } catch (e: Exception) {
            return SignInResult.Failed(e.message ?: "Apple sign-in failed")
        }
        val response = loginClient.authenticateSocial(
            AuthenticateSocialRequest {
                provider = Provider.PROVIDER_APPLE
                this.idToken = idToken
            },
        )
        return response.toSignInResult(account)
    }
}
