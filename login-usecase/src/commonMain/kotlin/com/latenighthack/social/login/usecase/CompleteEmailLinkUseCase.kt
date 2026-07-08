package com.latenighthack.social.login.usecase

import com.latenighthack.social.account.domain.AccountManager
import com.latenighthack.social.login.domain.LoginClient
import com.latenighthack.social.login.v1.CompleteEmailLinkRequest

/**
 * Completes an email magic-link sign-in with the token from the link, then either recovers the bound
 * account key or reports that binding is needed.
 */
class CompleteEmailLinkUseCase(
    private val loginClient: LoginClient,
    private val account: AccountManager,
) {
    suspend fun complete(email: String, token: String): SignInResult =
        loginClient.completeEmailLink(
            CompleteEmailLinkRequest {
                this.email = email
                this.token = token
            },
        ).toSignInResult(account)
}
