package com.latenighthack.social.login.core.service

import java.security.SecureRandom
import java.util.Base64

/**
 * Shared deployment configuration for the login core, read from environment variables. Provider-
 * specific configuration (SMTP/SendGrid/Twilio credentials, Apple/Google audiences) is read by each
 * provider's [LoginProviderFactory] from the same environment.
 */
class LoginConfig(
    val masterKey: ByteArray,
    val linkBaseUrl: String,
    // When true, AuthenticateSocial rejects requests without a valid server-issued nonce
    // (LOGIN_REQUIRE_NONCE=true). Off by default so pre-nonce clients keep working during rollout.
    val requireNonce: Boolean = false,
) {
    companion object {
        fun fromEnv(env: (String) -> String? = System::getenv): LoginConfig {
            val masterKeyValue = env("LOGIN_MASTER_KEY")
            val masterKey = if (masterKeyValue.isNullOrBlank()) {
                // Dev fallback: an ephemeral master key. Custodial data cannot be decrypted across a
                // restart — a production deployment MUST set LOGIN_MASTER_KEY to a stable 32-byte key.
                ByteArray(CustodyCrypto.KEY_BYTES).also(SecureRandom()::nextBytes)
            } else {
                Base64.getDecoder().decode(masterKeyValue)
            }
            return LoginConfig(
                masterKey = masterKey,
                linkBaseUrl = env("LOGIN_LINK_BASE_URL") ?: "https://example.invalid/login",
                requireNonce = env("LOGIN_REQUIRE_NONCE")?.toBoolean() ?: false,
            )
        }
    }
}
