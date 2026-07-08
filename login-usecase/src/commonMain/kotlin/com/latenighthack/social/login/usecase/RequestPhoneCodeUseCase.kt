package com.latenighthack.social.login.usecase

import com.latenighthack.social.login.domain.LoginClient
import com.latenighthack.social.login.v1.StartPhoneCodeRequest

/**
 * Requests an SMS one-time code for [phoneNumber] (E.164). The app later submits it via
 * [VerifyPhoneCodeUseCase].
 */
class RequestPhoneCodeUseCase(
    private val loginClient: LoginClient,
) {
    suspend fun request(phoneNumber: String): ChallengeResult =
        loginClient.startPhoneCode(StartPhoneCodeRequest { this.phoneNumber = phoneNumber }).result.toChallengeResult()
}
