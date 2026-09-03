package com.latenighthack.social.login.core.service

/**
 * The claims the login core needs from a verified OIDC id token. [subject] is the stable per-provider
 * user id; [nonce] is the token's `nonce` claim (checked against the server-issued value when nonce
 * enforcement is on); the rest are best-effort profile prefills (Google tokens carry name / picture /
 * email; Apple tokens carry email only — Apple's name never appears in the token).
 */
class VerifiedClaims(
    val subject: String,
    val nonce: String? = null,
    val displayName: String? = null,
    val photoUrl: String? = null,
    val email: String? = null,
)

/**
 * Verifies a social provider's OIDC id token and returns its claims, or null if the token is invalid.
 * A generic handler so Apple, Google, or a test double all satisfy the same contract. Concrete
 * JWKS-verifying implementations live in the per-provider service modules (login-apple-service /
 * login-google-service) so nimbus is only linked when social is used.
 */
interface SocialTokenVerifier {
    suspend fun verify(idToken: String): VerifiedClaims?
}

/** Trusts the token verbatim as the subject. For local development and tests only. */
class DevSocialTokenVerifier : SocialTokenVerifier {
    override suspend fun verify(idToken: String): VerifiedClaims? =
        idToken.ifBlank { null }?.let { VerifiedClaims(subject = it) }
}
