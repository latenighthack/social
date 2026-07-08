package com.latenighthack.social.login.phone.service

import com.latenighthack.social.login.core.service.LoginHandler
import com.latenighthack.social.login.core.service.LoginProviderContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlin.test.Test
import kotlin.test.assertTrue

class PhoneLoginProviderFactoryTest {

    @Test
    fun `defaults to a console sms sender`() {
        HttpClient(CIO).use { httpClient ->
            val handler = PhoneLoginProviderFactory().create(LoginProviderContext(env = { null }, httpClient = httpClient))
            assertTrue(handler is LoginHandler.Sms)
        }
    }
}
