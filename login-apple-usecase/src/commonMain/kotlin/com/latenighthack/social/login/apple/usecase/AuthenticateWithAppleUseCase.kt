package com.latenighthack.social.login.apple.usecase

import com.latenighthack.social.observability.*

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
) : SocialTelemetryOwner {
    override var socialTelemetry: SocialTelemetry = NoopSocialTelemetry

    suspend fun authenticate(): SignInResult = socialTelemetry.measure("login", "nativeSignIn", "apple") { (run observedOperation@ {
        // Native authorization is admitted only after a successful single-use nonce request.
        val nonce = try {
            val response = loginClient.requestNonce()
            if (response.result != com.latenighthack.social.login.v1.LoginResult.LOGIN_RESULT_OK || response.nonce.isBlank()) {
                return@observedOperation SignInResult.Failed("Could not obtain a sign-in nonce")
            }
            response.nonce
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return@observedOperation SignInResult.Failed(e.message ?: "Could not obtain a sign-in nonce")
        }
        val native = try {
            appleSignIn.signIn(nonce)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return@observedOperation SignInResult.Failed(e.message ?: "Apple sign-in failed")
        }
        val response = loginClient.authenticateSocial(
            AuthenticateSocialRequest {
                provider = Provider.PROVIDER_APPLE
                idToken = native.idToken
                this.nonce = nonce
            },
        )
        // Apple's name/email arrive only from the native credential (first authorization) — they win
        // over whatever the server read from the token (which never carries the name).
        val nativePrefill = LoginPrefill(displayName = native.displayName, email = native.email)
            .takeUnless { it.isEmpty() }
        return@observedOperation response.toSignInResult(account, nativePrefill)

        }) }


}
