package com.latenighthack.social.login.usecase

import com.latenighthack.social.account.domain.AccountManager
import com.latenighthack.social.login.domain.GoogleSignInClient
import com.latenighthack.social.login.domain.LoginClient
import com.latenighthack.social.login.v1.AuthenticateSocialRequest
import com.latenighthack.social.login.v1.Provider

/**
 * Signs in with Google: acquire a native Google id token, then either recover the bound account key
 * or report that binding is needed. On [SignInResult.NeedsBinding], the app creates or reuses an
 * account and calls [BindCurrentAccountUseCase].
 */
class AuthenticateWithGoogleUseCase(
    private val loginClient: LoginClient,
    private val googleSignIn: GoogleSignInClient,
    private val account: AccountManager,
) {
    suspend fun authenticate(): SignInResult {
        val idToken = try {
            googleSignIn.signIn()
        } catch (e: Exception) {
            return SignInResult.Failed(e.message ?: "Google sign-in failed")
        }
        val response = loginClient.authenticateSocial(
            AuthenticateSocialRequest {
                provider = Provider.PROVIDER_GOOGLE
                this.idToken = idToken
            },
        )
        return response.toSignInResult(account)
    }
}
