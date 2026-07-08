package com.latenighthack.social.login.apple.service

import com.latenighthack.social.login.core.service.SocialTokenVerifier
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
 * Verifies an RS256 OIDC id token against a provider's published JWKS, checking the signature and
 * that the issuer and audience match (the audience is this app's client id). Returns the `sub` claim
 * on success; the default nimbus processor also rejects expired tokens.
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
