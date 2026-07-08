package com.latenighthack.social.login.service

import java.security.SecureRandom
import java.util.Base64

/**
 * Deployment configuration for the login service, read from environment variables. Selecting an
 * email/SMS provider chooses which concrete handler the factory instantiates; the social audiences
 * are this app's Apple/Google client ids that a valid id token must be issued for.
 */
class LoginConfig(
    val masterKey: ByteArray,
    val emailProvider: String,
    val smsProvider: String,
    val emailFrom: String,
    val smtpHost: String,
    val smtpPort: Int,
    val smtpUser: String,
    val smtpPassword: String,
    val sendGridApiKey: String,
    val twilioAccountSid: String,
    val twilioAuthToken: String,
    val twilioFrom: String,
    val appleAudiences: Set<String>,
    val googleAudiences: Set<String>,
    val linkBaseUrl: String,
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
                emailProvider = env("LOGIN_EMAIL_PROVIDER")?.lowercase() ?: "console",
                smsProvider = env("LOGIN_SMS_PROVIDER")?.lowercase() ?: "console",
                emailFrom = env("LOGIN_EMAIL_FROM").orEmpty(),
                smtpHost = env("LOGIN_SMTP_HOST").orEmpty(),
                smtpPort = env("LOGIN_SMTP_PORT")?.toIntOrNull() ?: 587,
                smtpUser = env("LOGIN_SMTP_USER").orEmpty(),
                smtpPassword = env("LOGIN_SMTP_PASSWORD").orEmpty(),
                sendGridApiKey = env("LOGIN_SENDGRID_API_KEY").orEmpty(),
                twilioAccountSid = env("LOGIN_TWILIO_ACCOUNT_SID").orEmpty(),
                twilioAuthToken = env("LOGIN_TWILIO_AUTH_TOKEN").orEmpty(),
                twilioFrom = env("LOGIN_TWILIO_FROM").orEmpty(),
                appleAudiences = env("LOGIN_APPLE_AUDIENCES").toAudienceSet(),
                googleAudiences = env("LOGIN_GOOGLE_AUDIENCES").toAudienceSet(),
                linkBaseUrl = env("LOGIN_LINK_BASE_URL") ?: "https://example.invalid/login",
            )
        }

        private fun String?.toAudienceSet(): Set<String> =
            orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }
}
