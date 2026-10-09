package com.latenighthack.social.login.email.service

import com.latenighthack.social.login.core.service.LoginHandler
import com.latenighthack.social.login.core.service.LoginProviderContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlin.test.assertFailsWith

class EmailLoginProviderFactoryTest {

    @Test
    fun `console and unknown providers cannot silently enable production delivery`() {
        HttpClient(CIO).use { http ->
            for (provider in listOf("console", "typo")) assertFailsWith<Exception> {
                EmailLoginProviderFactory().create(LoginProviderContext({ if (it == "LOGIN_EMAIL_PROVIDER") provider else null }, http))
            }
        }
    }

    @Test
    fun `unconfigured delivery is unavailable`() {
        HttpClient(CIO).use { httpClient ->
            val handler = EmailLoginProviderFactory().create(LoginProviderContext(env = { null }, httpClient = httpClient))
            assertNull(handler)
        }
    }
}
