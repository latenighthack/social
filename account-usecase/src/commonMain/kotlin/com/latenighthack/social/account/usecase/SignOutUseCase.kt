package com.latenighthack.social.account.usecase

import com.latenighthack.social.account.domain.AccountManager

/**
 * Signs out by revoking the account identity. The manager observes the key going away and
 * advances its lifecycle to SignedOut.
 */
class SignOutUseCase(
    private val manager: AccountManager,
) {
    suspend fun execute(): AccountResult =
        try {
            manager.signOut()
            AccountResult.SignedOut
        } catch (e: Exception) {
            AccountResult.Error(e.message ?: "failed to sign out")
        }
}
