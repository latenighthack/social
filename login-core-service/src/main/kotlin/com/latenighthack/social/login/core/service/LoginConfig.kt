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
    // (LOGIN_REQUIRE_NONCE=true). Production requires it; only explicit development may disable it.
    val requireNonce: Boolean = true,
    val keyVersion: Int = 1,
    val previousKeys: Map<Int, ByteArray> = emptyMap(),
) {
    companion object {
        fun fromEnv(env: (String) -> String? = System::getenv): LoginConfig {
            val development = env("LOGIN_DEVELOPMENT_MODE")?.toBooleanStrict() ?: false
            val requireNonce = env("LOGIN_REQUIRE_NONCE")?.toBooleanStrict() ?: true
            require(requireNonce || development) { "nonce enforcement may only be disabled in development" }
            val masterKeyValue = env("LOGIN_MASTER_KEY")
            val masterKey = if (masterKeyValue.isNullOrBlank()) {
                // Dev fallback: an ephemeral master key. Custodial data cannot be decrypted across a
                // restart — a production deployment MUST set LOGIN_MASTER_KEY to a stable 32-byte key.
                require(development) { "LOGIN_MASTER_KEY must be configured" }
                ByteArray(CustodyCrypto.KEY_BYTES).also(SecureRandom()::nextBytes)
            } else {
                Base64.getDecoder().decode(masterKeyValue)
            }
            require(masterKey.size == CustodyCrypto.KEY_BYTES)
            val version = env("LOGIN_MASTER_KEY_VERSION")?.toInt() ?: 1
            val previous = env("LOGIN_PREVIOUS_MASTER_KEYS").orEmpty().split(',').filter { it.isNotBlank() }.associate { item ->
                val parts = item.trim().split(':', limit = 2)
                require(parts.size == 2)
                parts[0].toInt() to Base64.getDecoder().decode(parts[1])
            }
            require(version > 0 && version !in previous && previous.all { it.key >= 0 && it.value.size == 32 })
            return LoginConfig(
                masterKey = masterKey,
                linkBaseUrl = env("LOGIN_LINK_BASE_URL") ?: "https://example.invalid/login",
                requireNonce = requireNonce,
                keyVersion = version,
                previousKeys = previous,
            )
        }
    }
}
