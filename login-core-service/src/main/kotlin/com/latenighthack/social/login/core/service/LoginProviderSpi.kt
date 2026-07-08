package com.latenighthack.social.login.core.service

import com.latenighthack.social.login.v1.Provider
import io.ktor.client.HttpClient

/**
 * The SPI by which a per-provider service module contributes its handler into the core login service.
 * Each provider module ships one [LoginProviderFactory] registered via `META-INF/services`; the core
 * [LoginServerExtensionFactory] discovers them with [java.util.ServiceLoader] and enables whatever is
 * on the classpath. Mirrors the lockers `ServerExtensionFactory` seam one level down.
 */
interface LoginProviderFactory {
    /** The handler this provider contributes, or null if its configuration is absent/disabled. */
    fun create(context: LoginProviderContext): LoginHandler?
}

/** What a provider factory is given: environment access and a shared HTTP client for REST senders. */
class LoginProviderContext(
    val env: (String) -> String?,
    val httpClient: HttpClient,
)

/** A contributed handler, folded by the core into the matching [LoginServiceImpl] slot. */
sealed interface LoginHandler {
    class SocialVerifier(val provider: Provider, val verifier: SocialTokenVerifier) : LoginHandler

    class Email(val sender: EmailSender) : LoginHandler

    class Sms(val sender: SmsSender) : LoginHandler
}
