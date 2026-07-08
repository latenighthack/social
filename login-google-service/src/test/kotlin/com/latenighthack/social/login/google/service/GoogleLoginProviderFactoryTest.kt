package com.latenighthack.social.login.google.service

import com.latenighthack.social.login.core.service.LoginHandler
import com.latenighthack.social.login.core.service.LoginProviderContext
import com.latenighthack.social.login.v1.Provider
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoogleLoginProviderFactoryTest {

    @Test
    fun `contributes a google social verifier`() {
        HttpClient(CIO).use { httpClient ->
            val context = LoginProviderContext(env = { null }, httpClient = httpClient)
            val handler = GoogleLoginProviderFactory().create(context)
            assertTrue(handler is LoginHandler.SocialVerifier)
            val googleProvider: Provider = Provider.PROVIDER_GOOGLE
            assertEquals(googleProvider.value, handler.provider.value)
        }
    }
}
