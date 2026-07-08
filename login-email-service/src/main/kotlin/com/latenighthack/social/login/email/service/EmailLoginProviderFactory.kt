package com.latenighthack.social.login.email.service

import com.latenighthack.social.login.core.service.EmailSender
import com.latenighthack.social.login.core.service.LoginHandler
import com.latenighthack.social.login.core.service.LoginProviderContext
import com.latenighthack.social.login.core.service.LoginProviderFactory

/**
 * Registers email magic-link delivery with the core login service. Being on the classpath enables
 * email; `LOGIN_EMAIL_PROVIDER` (`smtp` | `sendgrid` | else console) selects the transport, with
 * `LOGIN_EMAIL_FROM` and the provider's credentials read from the environment.
 */
class EmailLoginProviderFactory : LoginProviderFactory {
    override fun create(context: LoginProviderContext): LoginHandler {
        val from = context.env("LOGIN_EMAIL_FROM").orEmpty()
        val sender: EmailSender = when (context.env("LOGIN_EMAIL_PROVIDER")?.lowercase()) {
            "smtp" -> SmtpEmailSender(
                host = context.env("LOGIN_SMTP_HOST").orEmpty(),
                port = context.env("LOGIN_SMTP_PORT")?.toIntOrNull() ?: 587,
                username = context.env("LOGIN_SMTP_USER").orEmpty(),
                password = context.env("LOGIN_SMTP_PASSWORD").orEmpty(),
                from = from,
            )
            "sendgrid" -> SendGridEmailSender(
                apiKey = context.env("LOGIN_SENDGRID_API_KEY").orEmpty(),
                from = from,
                httpClient = context.httpClient,
            )
            else -> ConsoleEmailSender()
        }
        return LoginHandler.Email(sender)
    }
}
