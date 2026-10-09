package com.latenighthack.social.login.apple.usecase

import com.latenighthack.ktstore.InMemoryKeyValueStoreDelegate
import com.latenighthack.ktstore.KeyValueStore
import com.latenighthack.social.account.domain.AccountManagerImpl
import com.latenighthack.social.login.apple.domain.AppleSignInClient
import com.latenighthack.social.login.apple.domain.AppleSignInResult
import com.latenighthack.social.login.core.domain.LoginClient
import com.latenighthack.social.login.v1.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class NonceCancellationRegressionTest {
    @Test fun cancelledNonceDoesNotOpenNativeSignIn() = runTest {
        val client = object : LoginClient {
            override suspend fun requestNonce(): RequestNonceResponse = throw CancellationException("cancelled")
            override suspend fun authenticateSocial(request: AuthenticateSocialRequest): AuthenticateResponse = error("unexpected")
            override suspend fun startEmailLink(request: StartEmailLinkRequest): StartChallengeResponse = error("unexpected")
            override suspend fun completeEmailLink(request: CompleteEmailLinkRequest): AuthenticateResponse = error("unexpected")
            override suspend fun startPhoneCode(request: StartPhoneCodeRequest): StartChallengeResponse = error("unexpected")
            override suspend fun verifyPhoneCode(request: VerifyPhoneCodeRequest): AuthenticateResponse = error("unexpected")
            override suspend fun bind(request: BindRequest): BindResponse = error("unexpected")
        }
        var invoked = false
        val native = object : AppleSignInClient {
            override suspend fun signIn(nonce: String?): AppleSignInResult { invoked = true; error("unexpected") }
        }
        val useCase = AuthenticateWithAppleUseCase(client, native, AccountManagerImpl(KeyValueStore(InMemoryKeyValueStoreDelegate())))
        assertFailsWith<CancellationException> { useCase.authenticate() }
        assertFalse(invoked)
    }
}
