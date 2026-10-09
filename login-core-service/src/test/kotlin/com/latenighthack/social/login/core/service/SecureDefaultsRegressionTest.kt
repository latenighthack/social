package com.latenighthack.social.login.core.service

import com.latenighthack.social.login.v1.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SecureDefaultsRegressionTest {
    @Test fun `default service rejects a nonce-less signed-in subject`() = runTest {
        val db = LoginStorage.inMemory()
        db.open()
        val verifier = object : SocialTokenVerifier {
            override suspend fun verify(idToken: String) = VerifiedClaims(subject = "subject")
        }
        val service = LoginServiceImpl(CredentialStore(db), ChallengeStore(db), CustodyCrypto(ByteArray(32)),
            Pbkdf2Hasher(1000), verifier, verifier, null, null, "https://app.test/login")
        val result = LocalLoginServiceRpc(service).authenticateSocial(AuthenticateSocialRequest {
            provider = Provider.PROVIDER_GOOGLE; idToken = "valid-token"
        })
        assertEquals(LoginResult.LOGIN_RESULT_UNAUTHORIZED, result.result)
    }
}
