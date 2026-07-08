package com.latenighthack.social.login.service

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Verifies a social provider's OIDC id token and returns its subject (the stable per-provider user
 * id), or null if the token is invalid. A generic handler so Apple, Google, or a test double all
 * satisfy the same contract.
 */
interface SocialTokenVerifier {
    suspend fun verify(idToken: String): String?
}

/**
 * Verifies an RS256 OIDC id token against a provider's published JWKS, checking the signature and
 * that the issuer and audience match the expected values (the audience is this app's client id).
 * Returns the `sub` claim on success. The default nimbus processor also rejects expired tokens.
 */
class OidcTokenVerifier(
    private val issuers: Set<String>,
    jwksUrl: String,
    private val audiences: Set<String>,
) : SocialTokenVerifier {
    private val processor = DefaultJWTProcessor<SecurityContext>().apply {
        val source: JWKSource<SecurityContext> = JWKSourceBuilder.create<SecurityContext>(URL(jwksUrl)).build()
        jwsKeySelector = JWSVerificationKeySelector(JWSAlgorithm.RS256, source)
    }

    override suspend fun verify(idToken: String): String? = withContext(Dispatchers.IO) {
        val claims = try {
            processor.process(idToken, null)
        } catch (e: Exception) {
            return@withContext null
        }
        if (claims.issuer !in issuers) return@withContext null
        val audience = claims.audience ?: emptyList()
        if (audiences.isNotEmpty() && audience.none { it in audiences }) return@withContext null
        claims.subject
    }
}

/** Trusts the token verbatim as the subject. For local development and tests only. */
class DevSocialTokenVerifier : SocialTokenVerifier {
    override suspend fun verify(idToken: String): String? = idToken.ifBlank { null }
}

/** Concrete verifiers for the supported providers, configured with the app's accepted audiences. */
object SocialTokenVerifiers {
    fun apple(audiences: Set<String>): SocialTokenVerifier = OidcTokenVerifier(
        issuers = setOf("https://appleid.apple.com"),
        jwksUrl = "https://appleid.apple.com/auth/keys",
        audiences = audiences,
    )

    fun google(audiences: Set<String>): SocialTokenVerifier = OidcTokenVerifier(
        issuers = setOf("https://accounts.google.com", "accounts.google.com"),
        jwksUrl = "https://www.googleapis.com/oauth2/v3/certs",
        audiences = audiences,
    )
}
