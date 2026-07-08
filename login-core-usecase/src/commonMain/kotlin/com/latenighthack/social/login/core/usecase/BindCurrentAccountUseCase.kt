package com.latenighthack.social.login.core.usecase

import com.latenighthack.social.account.domain.AccountManager
import com.latenighthack.social.login.core.domain.LoginClient
import com.latenighthack.social.login.v1.BindRequest
import com.latenighthack.social.login.v1.LoginResult

/**
 * Binds the current account to a just-proven login method, using the bind ticket from a
 * [SignInResult.NeedsBinding]. Exports the account's identity (id + private key) and hands it to the
 * custodial service, which holds it so the method can recover the account on another device. Used for
 * sign-up (after createAccount) and for linking an additional method to the signed-in account.
 */
class BindCurrentAccountUseCase(
    private val loginClient: LoginClient,
    private val account: AccountManager,
) {
    suspend fun bind(bindTicket: ByteArray): BindResult {
        val identity = try {
            account.exportIdentity()
        } catch (e: Exception) {
            return BindResult.Failed(e.message ?: "no account to bind")
        }
        val response = loginClient.bind(
            BindRequest {
                this.bindTicket = bindTicket
                accountId = identity.accountId
                accountPrivateKey = identity.privateKey
            },
        )
        return when (response.result) {
            LoginResult.LOGIN_RESULT_OK -> BindResult.Bound
            LoginResult.LOGIN_RESULT_ALREADY_BOUND ->
                BindResult.Failed("this login method is already bound to another account")
            LoginResult.LOGIN_RESULT_EXPIRED -> BindResult.Failed("the bind ticket expired")
            else -> BindResult.Failed("bind failed")
        }
    }
}
