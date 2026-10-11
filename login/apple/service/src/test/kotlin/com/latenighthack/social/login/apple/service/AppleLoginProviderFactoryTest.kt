package com.latenighthack.social.login.apple.service

import com.latenighthack.social.login.core.service.LoginHandler
import com.latenighthack.social.login.core.service.LoginProviderContext
import com.latenighthack.social.login.v1.Provider
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class AppleLoginProviderFactoryTest {

    @Test
    fun `missing audiences cannot enable a verifier`() {
        HttpClient(CIO).use { httpClient ->
            assertFailsWith<IllegalArgumentException> {
                AppleLoginProviderFactory().create(LoginProviderContext(env = { null }, httpClient = httpClient))
            }
        }
    }

    @Test
    fun `contributes an apple social verifier`() {
        HttpClient(CIO).use { httpClient ->
            val context = LoginProviderContext(env = { key -> if (key == "LOGIN_APPLE_AUDIENCES") "test.client" else null }, httpClient = httpClient)
            val handler = AppleLoginProviderFactory().create(context)
            assertTrue(handler is LoginHandler.SocialVerifier)
            val appleProvider: Provider = Provider.PROVIDER_APPLE
            assertEquals(appleProvider.value, handler.provider.value)
        }
    }
}
