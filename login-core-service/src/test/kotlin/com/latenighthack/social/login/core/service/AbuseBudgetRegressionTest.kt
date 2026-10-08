package com.latenighthack.social.login.core.service

import com.latenighthack.social.login.v1.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AbuseBudgetRegressionTest {
    @Test fun resendingDoesNotResetAttemptsOrRepeatDelivery() = runTest {
        val database = LoginStorage.inMemory("abuse-budget")
        database.open()
        var deliveries = 0
        var time = 100L
        val service = LoginServiceImpl(CredentialStore(database), ChallengeStore(database), CustodyCrypto(ByteArray(32) { 1 }),
            Pbkdf2Hasher(1000), null, null, null,
            object : SmsSender { override suspend fun sendCode(phoneNumber: String, code: String) { deliveries++ } },
            "https://test", clock = { time }, maxAttempts = 1)
        val rpc = LocalLoginServiceRpc(service)
        val start = StartPhoneCodeRequest(phoneNumber = "+15551234567")
        assertEquals(LoginResult.LOGIN_RESULT_OK, rpc.startPhoneCode(start).result)
        assertEquals(LoginResult.LOGIN_RESULT_RATE_LIMITED, rpc.startPhoneCode(start).result)
        assertEquals(1, deliveries)
        assertEquals(LoginResult.LOGIN_RESULT_EXHAUSTED, rpc.verifyPhoneCode(VerifyPhoneCodeRequest(phoneNumber = start.phoneNumber, code = "invalid")).result)
        assertEquals(LoginResult.LOGIN_RESULT_RATE_LIMITED, rpc.startPhoneCode(start).result)
        time += 60_000
        assertEquals(LoginResult.LOGIN_RESULT_OK, rpc.startPhoneCode(start).result)
        assertEquals(2, deliveries)
    }
}
