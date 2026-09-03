package com.latenighthack.social.login.apple.domain

/**
 * What native Sign in with Apple returned. [idToken] is the OIDC identity token the login service
 * verifies. [displayName]/[email] are captured from the native credential — Apple supplies the
 * user's name ONLY on the very first authorization and it never appears in the token, so it must be
 * caught here or lost; both are best-effort signup prefills.
 */
class AppleSignInResult(
    val idToken: String,
    val displayName: String? = null,
    val email: String? = null,
)

/**
 * Acquires an Apple id token via native Sign in with Apple. Implemented per platform (iOS uses the
 * system AuthenticationServices framework); the returned OIDC id token is handed to the login service
 * for verification. [nonce] is the raw server-issued replay nonce — implementations bind its SHA-256
 * hex into the request so the token's `nonce` claim proves freshness. Throws if the user cancels or
 * no native path is available on the platform.
 */
interface AppleSignInClient {
    suspend fun signIn(nonce: String? = null): AppleSignInResult
}
