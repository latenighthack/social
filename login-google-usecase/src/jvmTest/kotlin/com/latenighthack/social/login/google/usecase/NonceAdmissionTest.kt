package com.latenighthack.social.login.google.usecase

import com.latenighthack.ktstore.InMemoryKeyValueStoreDelegate
import com.latenighthack.ktstore.KeyValueStore
import com.latenighthack.social.account.domain.AccountManagerImpl
import com.latenighthack.social.login.google.domain.*
import com.latenighthack.social.login.core.domain.LoginClient
import com.latenighthack.social.login.core.usecase.SignInResult
import com.latenighthack.social.login.v1.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class NonceAdmissionTest {
    @Test fun denialEmptyNonceAndTransportFailureNeverOpenNativeSignIn() = runTest {
        for (response in listOf(RequestNonceResponse { result = LoginResult.LOGIN_RESULT_RATE_LIMITED },
            RequestNonceResponse { result = LoginResult.LOGIN_RESULT_OK }, null)) {
            val client = object : LoginClient {
                override suspend fun requestNonce(): RequestNonceResponse = response ?: error("unavailable")
                override suspend fun authenticateSocial(request: AuthenticateSocialRequest): AuthenticateResponse = error("unexpected")
                override suspend fun startEmailLink(request: StartEmailLinkRequest): StartChallengeResponse = error("unexpected")
                override suspend fun completeEmailLink(request: CompleteEmailLinkRequest): AuthenticateResponse = error("unexpected")
                override suspend fun startPhoneCode(request: StartPhoneCodeRequest): StartChallengeResponse = error("unexpected")
                override suspend fun verifyPhoneCode(request: VerifyPhoneCodeRequest): AuthenticateResponse = error("unexpected")
                override suspend fun bind(request: BindRequest): BindResponse = error("unexpected")
            }
            var invoked = false
            val native = object : GoogleSignInClient {
                override suspend fun signIn(nonce: String?): String { invoked = true; error("unexpected") }
            }
            val account = AccountManagerImpl(KeyValueStore(InMemoryKeyValueStoreDelegate()))
            assertIs<SignInResult.Failed>(AuthenticateWithGoogleUseCase(client, native, account).authenticate())
            assertFalse(invoked)
        }
    }
}
