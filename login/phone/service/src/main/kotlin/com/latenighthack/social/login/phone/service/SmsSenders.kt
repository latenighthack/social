package com.latenighthack.social.login.phone.service

import com.latenighthack.social.login.core.service.SmsSender
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import java.net.URLEncoder
import java.util.Base64

/** Logs the code instead of sending. For local development and tests only. */
class ConsoleSmsSender : SmsSender {
    override suspend fun sendCode(phoneNumber: String, code: String) {
        println("[login] sms code for $phoneNumber: $code")
    }
}

/** Sends through the Twilio Messages REST API over a shared ktor client (no vendor SDK). */
class TwilioSmsSender(
    private val accountSid: String,
    private val authToken: String,
    private val fromNumber: String,
    private val httpClient: HttpClient,
) : SmsSender {
    override suspend fun sendCode(phoneNumber: String, code: String) {
        val form = listOf(
            "To" to phoneNumber,
            "From" to fromNumber,
            "Body" to "Your verification code is $code",
        ).joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val basic = Base64.getEncoder().encodeToString("$accountSid:$authToken".toByteArray())
        val response = httpClient.post("https://api.twilio.com/2010-04-01/Accounts/$accountSid/Messages.json") {
            header(HttpHeaders.Authorization, "Basic $basic")
            header(HttpHeaders.ContentType, "application/x-www-form-urlencoded")
            setBody(form)
        }
        check(response.status.value in 200..299) { "Twilio send failed: ${response.status}" }
    }
}
