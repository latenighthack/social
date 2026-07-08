package com.latenighthack.social.login.core.usecase

import com.latenighthack.social.account.domain.AccountManager
import com.latenighthack.social.login.v1.AuthenticateResponse
import com.latenighthack.social.login.v1.LoginResult

/**
 * The outcome of proving a login method. Either the server held a bound account and its key was
 * recovered (and adopted via the account manager), or the method is not yet bound and a bind ticket
 * is returned for the caller to attach an account with [BindCurrentAccountUseCase].
 */
sealed interface SignInResult {
    /** The account was recovered and restored; [accountId] is its 33-byte compressed public key. */
    data class Recovered(val accountId: ByteArray) : SignInResult

    /** The method was proven but no account is bound; pass [bindTicket] to bind the current account. */
    data class NeedsBinding(val bindTicket: ByteArray) : SignInResult

    data class Failed(val message: String) : SignInResult
}

/** The outcome of starting an email-link or phone-code challenge. */
sealed interface ChallengeResult {
    data object Started : ChallengeResult

    data class Failed(val message: String) : ChallengeResult
}

/** The outcome of binding the current account to a proven login method. */
sealed interface BindResult {
    data object Bound : BindResult

    data class Failed(val message: String) : BindResult
}

/**
 * Map an authenticate/complete/verify response to a [SignInResult]. On OK, adopt the recovered key
 * via [AccountManager.restoreAccount] so the account is live on this device.
 */
suspend fun AuthenticateResponse.toSignInResult(account: AccountManager): SignInResult =
    when (result) {
        LoginResult.LOGIN_RESULT_OK -> {
            val recovered = identity
            if (recovered == null) {
                SignInResult.Failed("server returned no identity")
            } else {
                try {
                    SignInResult.Recovered(account.restoreAccount(recovered.accountPrivateKey))
                } catch (e: Exception) {
                    SignInResult.Failed(e.message ?: "failed to restore account")
                }
            }
        }
        LoginResult.LOGIN_RESULT_NEEDS_BINDING -> SignInResult.NeedsBinding(bindTicket)
        LoginResult.LOGIN_RESULT_EXPIRED -> SignInResult.Failed("the sign-in challenge expired")
        LoginResult.LOGIN_RESULT_EXHAUSTED -> SignInResult.Failed("too many attempts")
        LoginResult.LOGIN_RESULT_UNAUTHORIZED -> SignInResult.Failed("the sign-in token was rejected")
        LoginResult.LOGIN_RESULT_PROVIDER_UNAVAILABLE -> SignInResult.Failed("this sign-in method is not available")
        else -> SignInResult.Failed("invalid code")
    }

fun LoginResult.toChallengeResult(): ChallengeResult =
    when (this) {
        LoginResult.LOGIN_RESULT_OK -> ChallengeResult.Started
        LoginResult.LOGIN_RESULT_PROVIDER_UNAVAILABLE -> ChallengeResult.Failed("this sign-in method is not available")
        else -> ChallengeResult.Failed("could not start the challenge")
    }
