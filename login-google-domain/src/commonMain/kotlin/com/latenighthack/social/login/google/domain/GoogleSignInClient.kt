package com.latenighthack.social.login.google.domain

/**
 * Acquires a Google id token via native Google sign-in. Implemented per platform (Android uses the
 * Credential Manager + Google Identity); the returned OIDC id token is handed to the login service
 * for verification. [nonce] is the raw server-issued replay nonce, passed verbatim to the request so
 * it appears as the token's `nonce` claim. Throws if the user cancels or no native path is available
 * on the platform.
 */
interface GoogleSignInClient {
    suspend fun signIn(nonce: String? = null): String
}
