package com.latenighthack.social.login.core.service

/**
 * Delivers a one-time code by SMS. A generic handler so a deployment can plug in any provider; the
 * concrete implementations (Twilio / console) live in login-phone-service and register via the
 * [LoginProviderFactory] SPI.
 */
interface SmsSender {
    suspend fun sendCode(phoneNumber: String, code: String)
}
