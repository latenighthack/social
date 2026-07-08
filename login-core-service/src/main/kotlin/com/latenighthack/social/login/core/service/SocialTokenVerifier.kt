package com.latenighthack.social.login.core.service

/**
 * Verifies a social provider's OIDC id token and returns its subject (the stable per-provider user
 * id), or null if the token is invalid. A generic handler so Apple, Google, or a test double all
 * satisfy the same contract. Concrete JWKS-verifying implementations live in the per-provider service
 * modules (login-apple-service / login-google-service) so nimbus is only linked when social is used.
 */
interface SocialTokenVerifier {
    suspend fun verify(idToken: String): String?
}

/** Trusts the token verbatim as the subject. For local development and tests only. */
class DevSocialTokenVerifier : SocialTokenVerifier {
    override suspend fun verify(idToken: String): String? = idToken.ifBlank { null }
}
