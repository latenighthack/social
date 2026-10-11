package com.latenighthack.social.account.usecase

/** The outcome of an account use case. */
sealed interface AccountResult {
    /** An account key exists; [accountId] is the 33-byte compressed public key. */
    data class Ready(val accountId: ByteArray) : AccountResult

    /** The account was signed out. */
    data object SignedOut : AccountResult

    data class Error(val message: String) : AccountResult
}
