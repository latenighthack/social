package com.latenighthack.social.login.core.service

import com.latenighthack.social.login.v1.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class LoginExtensionClockTest {
    @Test fun extensionUsesOneClockForDurableCooldownAndNonceExpiry() = runBlocking {
        var now = 100_000L
        val database = LoginStorage.inMemory("extension-clock"); database.open()
        val extension = LoginServerExtension(database, CustodyCrypto(ByteArray(32) { 1 }), Pbkdf2Hasher(iterations = 1000),
            appleVerifier = object : SocialTokenVerifier {
                override suspend fun verify(idToken: String) = VerifiedClaims("clock-user", nonce = idToken)
            }, googleVerifier = null, emailSender = null,
            smsSender = object : SmsSender { override suspend fun sendCode(phoneNumber: String, code: String) = Unit },
            linkBaseUrl = "https://example.test/login", clock = { now })
        try {
            extension.start()
            val service = LocalLoginServiceRpc(extension.services.single().server as LoginServer)
            val request = StartPhoneCodeRequest(phoneNumber = "+15551234567")
            assertEquals(LoginResult.LOGIN_RESULT_OK, service.startPhoneCode(request).result)
            assertEquals(LoginResult.LOGIN_RESULT_RATE_LIMITED, service.startPhoneCode(request).result)
            now += 60_001
            assertEquals(LoginResult.LOGIN_RESULT_OK, service.startPhoneCode(request).result)
            val nonce = service.requestNonce(RequestNonceRequest()).nonce
            now += 300_000
            assertEquals(LoginResult.LOGIN_RESULT_UNAUTHORIZED, service.authenticateSocial(AuthenticateSocialRequest(
                provider = Provider.PROVIDER_APPLE, idToken = nonce, nonce = nonce)).result)
        } finally { extension.stop(); database.close() }
    }
}
