package com.latenighthack.social.account.usecase

import com.latenighthack.social.account.domain.AccountManager

/**
 * Creates the account identity if none exists. This unblocks the session (which waits on the
 * account key); the manager then advances its lifecycle to Ready.
 */
class CreateAccountUseCase(
    private val manager: AccountManager,
) {
    suspend fun execute(): AccountResult =
        try {
            AccountResult.Ready(manager.createAccount())
        } catch (e: Exception) {
            AccountResult.Error(e.message ?: "failed to create account")
        }
}
