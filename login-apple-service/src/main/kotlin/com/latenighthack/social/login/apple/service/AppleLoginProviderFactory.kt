package com.latenighthack.social.login.apple.service

import com.latenighthack.social.login.core.service.LoginHandler
import com.latenighthack.social.login.core.service.LoginProviderContext
import com.latenighthack.social.login.core.service.LoginProviderFactory
import com.latenighthack.social.login.v1.Provider

/**
 * Registers Apple id-token verification with the core login service. Simply being on the classpath
 * enables Apple; `LOGIN_APPLE_AUDIENCES` (comma-separated) restricts the accepted token audience to
 * this app's Apple client id(s).
 */
class AppleLoginProviderFactory : LoginProviderFactory {
    override fun create(context: LoginProviderContext): LoginHandler {
        val audiences = context.env("LOGIN_APPLE_AUDIENCES")
            .orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        return LoginHandler.SocialVerifier(
            Provider.PROVIDER_APPLE,
            OidcTokenVerifier(
                issuers = setOf("https://appleid.apple.com"),
                jwksUrl = "https://appleid.apple.com/auth/keys",
                audiences = audiences,
            ),
        )
    }
}
