package com.latenighthack.social.login.google.service

import com.latenighthack.social.login.core.service.LoginHandler
import com.latenighthack.social.login.core.service.LoginProviderContext
import com.latenighthack.social.login.core.service.LoginProviderFactory
import com.latenighthack.social.login.v1.Provider

/**
 * Registers Google id-token verification with the core login service. Simply being on the classpath
 * enables Google; `LOGIN_GOOGLE_AUDIENCES` (comma-separated) restricts the accepted token audience to
 * this app's Google client id(s).
 */
class GoogleLoginProviderFactory : LoginProviderFactory {
    override fun create(context: LoginProviderContext): LoginHandler {
        val audiences = context.env("LOGIN_GOOGLE_AUDIENCES")
            .orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        return LoginHandler.SocialVerifier(
            Provider.PROVIDER_GOOGLE,
            OidcTokenVerifier(
                issuers = setOf("https://accounts.google.com", "accounts.google.com"),
                jwksUrl = "https://www.googleapis.com/oauth2/v3/certs",
                audiences = audiences,
            ),
        )
    }
}
