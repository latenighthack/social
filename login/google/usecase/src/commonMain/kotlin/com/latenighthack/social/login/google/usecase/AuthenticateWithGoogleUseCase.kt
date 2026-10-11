package com.latenighthack.social.login.google.usecase

import com.latenighthack.social.observability.*

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
) : SocialTelemetryOwner {
    override var socialTelemetry: SocialTelemetry = NoopSocialTelemetry

    suspend fun authenticate(): SignInResult = socialTelemetry.measure("login", "nativeSignIn", "google") { (run observedOperation@ {
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
        val idToken = try {
            googleSignIn.signIn(nonce)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return@observedOperation SignInResult.Failed(e.message ?: "Google sign-in failed")
        }
        val response = loginClient.authenticateSocial(
            AuthenticateSocialRequest {
                provider = Provider.PROVIDER_GOOGLE
                this.idToken = idToken
                this.nonce = nonce
            },
        )
        return@observedOperation response.toSignInResult(account)

        }) }


}
