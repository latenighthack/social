package com.latenighthack.social.login.phone.service

import com.latenighthack.social.login.core.service.LoginHandler
import com.latenighthack.social.login.core.service.LoginProviderContext
import com.latenighthack.social.login.core.service.LoginProviderFactory
import com.latenighthack.social.login.core.service.SmsSender

/**
 * Registers phone OTP delivery with the core login service. Being on the classpath enables phone;
 * `LOGIN_SMS_PROVIDER` (`twilio` | else console) selects the transport, with the Twilio credentials
 * read from the environment.
 */
class PhoneLoginProviderFactory : LoginProviderFactory {
    override fun create(context: LoginProviderContext): LoginHandler? {
        val selected = context.env("LOGIN_SMS_PROVIDER")?.lowercase() ?: return null
        val development = context.env("LOGIN_DEVELOPMENT_MODE")?.toBooleanStrict() ?: false
        val sender: SmsSender = when (selected) {
            "twilio" -> TwilioSmsSender(
                accountSid = context.env("LOGIN_TWILIO_ACCOUNT_SID").orEmpty(),
                authToken = context.env("LOGIN_TWILIO_AUTH_TOKEN").orEmpty(),
                fromNumber = context.env("LOGIN_TWILIO_FROM").orEmpty(),
                httpClient = context.httpClient,
            )
            "console" -> {
                require(development) { "console delivery requires explicit development mode" }
                ConsoleSmsSender()
            }
            else -> error("unknown login delivery provider")
        }
        return LoginHandler.Sms(sender)
    }
}
