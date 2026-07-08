package com.latenighthack.social.login.usecase

import com.latenighthack.social.account.domain.AccountManager
import com.latenighthack.social.login.domain.LoginClient
import com.latenighthack.social.login.v1.VerifyPhoneCodeRequest

/**
 * Verifies an SMS one-time code, then either recovers the bound account key or reports that binding
 * is needed.
 */
class VerifyPhoneCodeUseCase(
    private val loginClient: LoginClient,
    private val account: AccountManager,
) {
    suspend fun verify(phoneNumber: String, code: String): SignInResult =
        loginClient.verifyPhoneCode(
            VerifyPhoneCodeRequest {
                this.phoneNumber = phoneNumber
                this.code = code
            },
        ).toSignInResult(account)
}
