package com.latenighthack.social.login.google.usecase

import com.latenighthack.social.account.domain.AccountManager
import com.latenighthack.social.login.core.domain.LoginClient
import com.latenighthack.social.login.core.usecase.SignInResult
import com.latenighthack.social.login.core.usecase.toSignInResult
import com.latenighthack.social.login.google.domain.GoogleSignInClient
import com.latenighthack.social.login.v1.AuthenticateSocialRequest
import com.latenighthack.social.login.v1.Provider

/**
 * Signs in with Google: acquire a native Google id token, then either recover the bound account key
 * or report that binding is needed. On [SignInResult.NeedsBinding], the app creates or reuses an
 * account and calls BindCurrentAccountUseCase.
 */
class AuthenticateWithGoogleUseCase(
    private val loginClient: LoginClient,
    private val googleSignIn: GoogleSignInClient,
    private val account: AccountManager,
) {
    suspend fun authenticate(): SignInResult {
        // Replay defense: bind a server-issued single-use nonce into the native request. Best-effort —
        // enforcement (and thus failure) is server-side.
        val nonce = try {
            loginClient.requestNonce().nonce.ifEmpty { null }
        } catch (e: Exception) {
            null
        }
        val idToken = try {
            googleSignIn.signIn(nonce)
        } catch (e: Exception) {
            return SignInResult.Failed(e.message ?: "Google sign-in failed")
        }
        val response = loginClient.authenticateSocial(
            AuthenticateSocialRequest {
                provider = Provider.PROVIDER_GOOGLE
                this.idToken = idToken
                nonce?.let { this.nonce = it }
            },
        )
        return response.toSignInResult(account)
    }
}
