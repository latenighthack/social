package com.latenighthack.social.login.core.service

/**
 * Delivers a magic-link email. A generic handler so a deployment can plug in any provider; the
 * concrete implementations (SMTP / SendGrid / console) live in login-email-service and register via
 * the [LoginProviderFactory] SPI. The link carries the single-use token the client submits back to
 * CompleteEmailLink.
 */
interface EmailSender {
    suspend fun sendMagicLink(email: String, link: String)
}
