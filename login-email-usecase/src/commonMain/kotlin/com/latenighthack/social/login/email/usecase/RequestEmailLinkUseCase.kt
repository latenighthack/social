package com.latenighthack.social.login.email.usecase

import com.latenighthack.social.login.core.domain.LoginClient
import com.latenighthack.social.login.core.usecase.ChallengeResult
import com.latenighthack.social.login.core.usecase.toChallengeResult
import com.latenighthack.social.login.v1.StartEmailLinkRequest

/**
 * Requests a magic-link email for [email]. The server delivers a single-use token by email; the app
 * later submits it via [CompleteEmailLinkUseCase]. Succeeds even for an unknown address (the server
 * does not disclose whether an address is registered).
 */
class RequestEmailLinkUseCase(
    private val loginClient: LoginClient,
) {
    suspend fun request(email: String): ChallengeResult =
        loginClient.startEmailLink(StartEmailLinkRequest { this.email = email }).result.toChallengeResult()
}
