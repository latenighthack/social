package com.latenighthack.social.login.apple.usecase

import com.latenighthack.social.account.domain.AccountManager
import com.latenighthack.social.login.apple.domain.AppleSignInClient
import com.latenighthack.social.login.core.domain.LoginClient
import com.latenighthack.social.login.core.usecase.LoginPrefill
import com.latenighthack.social.login.core.usecase.SignInResult
import com.latenighthack.social.login.core.usecase.toSignInResult
import com.latenighthack.social.login.v1.AuthenticateSocialRequest
import com.latenighthack.social.login.v1.Provider

/**
 * Signs in with Apple: acquire a native Apple id token, then either recover the bound account key or
 * report that binding is needed. On [SignInResult.NeedsBinding], the app creates or reuses an account
 * and calls BindCurrentAccountUseCase.
 */
class AuthenticateWithAppleUseCase(
    private val loginClient: LoginClient,
    private val appleSignIn: AppleSignInClient,
    private val account: AccountManager,
) {
    suspend fun authenticate(): SignInResult {
        // Replay defense: bind a server-issued single-use nonce into the native request. Best-effort —
        // an unreachable RequestNonce only matters when the server enforces nonces, and then the
        // authenticate call fails with a clear result anyway.
        val nonce = try {
            loginClient.requestNonce().nonce.ifEmpty { null }
        } catch (e: Exception) {
            null
        }
        val native = try {
            appleSignIn.signIn(nonce)
        } catch (e: Exception) {
            return SignInResult.Failed(e.message ?: "Apple sign-in failed")
        }
        val response = loginClient.authenticateSocial(
            AuthenticateSocialRequest {
                provider = Provider.PROVIDER_APPLE
                idToken = native.idToken
                nonce?.let { this.nonce = it }
            },
        )
        // Apple's name/email arrive only from the native credential (first authorization) — they win
        // over whatever the server read from the token (which never carries the name).
        val nativePrefill = LoginPrefill(displayName = native.displayName, email = native.email)
            .takeUnless { it.isEmpty() }
        return response.toSignInResult(account, nativePrefill)
    }
}
