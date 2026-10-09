package com.latenighthack.social.login.apple.service

import com.latenighthack.social.login.core.service.SocialTokenVerifier
import com.latenighthack.social.login.core.service.VerifiedClaims
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import java.net.URL
import com.nimbusds.jose.util.DefaultResourceRetriever
import com.nimbusds.jose.util.ResourceRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Verifies an RS256 OIDC id token against a provider's published JWKS, checking the signature and
 * that the issuer and audience match (the audience is this app's client id). Returns the verified
 * claims — subject, the `nonce` claim (for the core's replay check), and best-effort profile
 * prefills (`name`/`picture`/`email`) — on success; the default nimbus processor also rejects
 * expired tokens.
 */
class OidcTokenVerifier(
    private val issuers: Set<String>,
    jwksUrl: String,
    private val audiences: Set<String>,
    observeRefresh: (String, Long) -> Unit = { _, _ -> },
) : SocialTokenVerifier {
    private val processor = DefaultJWTProcessor<SecurityContext>().apply {
        val delegate = DefaultResourceRetriever(3_000, 5_000, 256 * 1024)
        val retriever = ResourceRetriever { url ->
            val start = System.nanoTime()
            var outcome = "success"
            try { delegate.retrieveResource(url) }
            catch (failure: Exception) { outcome = "error"; throw failure }
            finally { runCatching { observeRefresh(outcome, System.nanoTime() - start) } }
        }
        val source: JWKSource<SecurityContext> = JWKSourceBuilder.create<SecurityContext>(URL(jwksUrl), retriever).build()
        jwsKeySelector = JWSVerificationKeySelector(JWSAlgorithm.RS256, source)
    }

    override suspend fun verify(idToken: String): VerifiedClaims? = withContext(Dispatchers.IO) {
        val claims = try {
            processor.process(idToken, null)
        } catch (e: Exception) {
            return@withContext null
        }
        if (claims.issuer !in issuers) return@withContext null
        val audience = claims.audience ?: emptyList()
        if (audiences.isNotEmpty() && audience.none { it in audiences }) return@withContext null
        val subject = claims.subject ?: return@withContext null
        VerifiedClaims(
            subject = subject,
            nonce = claims.getClaim("nonce") as? String,
            displayName = claims.getClaim("name") as? String,
            photoUrl = claims.getClaim("picture") as? String,
            email = claims.getClaim("email") as? String,
        )
    }
}
