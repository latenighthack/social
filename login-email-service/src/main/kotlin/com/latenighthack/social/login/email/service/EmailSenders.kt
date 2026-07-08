package com.latenighthack.social.login.email.service

import com.latenighthack.social.login.core.service.EmailSender
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import java.util.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Logs the link instead of sending. For local development and tests only. */
class ConsoleEmailSender : EmailSender {
    override suspend fun sendMagicLink(email: String, link: String) {
        println("[login] magic link for $email: $link")
    }
}

/** Sends over SMTP with STARTTLS via Jakarta Mail. */
class SmtpEmailSender(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
    private val from: String,
) : EmailSender {
    override suspend fun sendMagicLink(email: String, link: String): Unit = withContext(Dispatchers.IO) {
        val props = Properties().apply {
            put("mail.smtp.auth", "true")
            put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.host", host)
            put("mail.smtp.port", port.toString())
        }
        val session = Session.getInstance(
            props,
            object : Authenticator() {
                override fun getPasswordAuthentication() = PasswordAuthentication(username, password)
            },
        )
        val message = MimeMessage(session)
        message.setFrom(InternetAddress(from))
        message.setRecipient(Message.RecipientType.TO, InternetAddress(email))
        message.setSubject("Your sign-in link")
        message.setText("Tap to sign in:\n\n$link\n\nIf you didn't request this, ignore this email.")
        Transport.send(message)
    }
}

/** Sends through the SendGrid v3 REST API over a shared ktor client (no vendor SDK). */
class SendGridEmailSender(
    private val apiKey: String,
    private val from: String,
    private val httpClient: HttpClient,
) : EmailSender {
    override suspend fun sendMagicLink(email: String, link: String) {
        val body = buildString {
            append("{\"personalizations\":[{\"to\":[{\"email\":")
            append(jsonString(email))
            append("}]}],\"from\":{\"email\":")
            append(jsonString(from))
            append("},\"subject\":\"Your sign-in link\",\"content\":[{\"type\":\"text/plain\",\"value\":")
            append(jsonString("Tap to sign in:\n\n$link"))
            append("}]}")
        }
        val response = httpClient.post("https://api.sendgrid.com/v3/mail/send") {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            header(HttpHeaders.ContentType, "application/json")
            setBody(body)
        }
        check(response.status.value in 200..299) { "SendGrid send failed: ${response.status}" }
    }
}

/** Minimal JSON string literal encoder for the handful of controlled REST payload fields. */
internal fun jsonString(value: String): String = buildString {
    append('"')
    for (c in value) {
        when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
    }
    append('"')
}
