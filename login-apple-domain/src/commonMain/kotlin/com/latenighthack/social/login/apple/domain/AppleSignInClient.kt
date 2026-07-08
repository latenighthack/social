package com.latenighthack.social.login.apple.domain

/**
 * Acquires an Apple id token via native Sign in with Apple. Implemented per platform (iOS uses the
 * system AuthenticationServices framework); the returned OIDC id token is handed to the login service
 * for verification. Throws if the user cancels or no native path is available on the platform.
 */
interface AppleSignInClient {
    suspend fun signIn(): String
}
