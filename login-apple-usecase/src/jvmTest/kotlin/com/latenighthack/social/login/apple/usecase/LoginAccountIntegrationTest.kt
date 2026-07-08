package com.latenighthack.social.login.apple.usecase

import com.latenighthack.ktbuf.net.RpcClient
import com.latenighthack.ktbuf.server.serveAll
import com.latenighthack.ktbuf.test.server.runTestWithServer
import com.latenighthack.ktstore.InMemoryKeyValueStoreDelegate
import com.latenighthack.ktstore.InMemoryStoreDelegate
import com.latenighthack.ktstore.KeyValueStore
import com.latenighthack.lockers.common.v1.Version
import com.latenighthack.lockers.connector.LockersClient
import com.latenighthack.lockers.server.attachTestServices
import com.latenighthack.lockers.server.rpcClient
import com.latenighthack.social.account.domain.AccountKeySource
import com.latenighthack.social.account.domain.AccountManager.Lifecycle
import com.latenighthack.social.account.domain.AccountManagerImpl
import com.latenighthack.social.login.apple.domain.AppleSignInClient
import com.latenighthack.social.login.core.domain.LoginClientImpl
import com.latenighthack.social.login.core.service.ChallengeStore
import com.latenighthack.social.login.core.service.CredentialStore
import com.latenighthack.social.login.core.service.CustodyCrypto
import com.latenighthack.social.login.core.service.DevSocialTokenVerifier
import com.latenighthack.social.login.core.service.LoginServiceImpl
import com.latenighthack.social.login.core.service.Pbkdf2Hasher
import com.latenighthack.social.login.core.usecase.BindCurrentAccountUseCase
import com.latenighthack.social.login.core.usecase.BindResult
import com.latenighthack.social.login.core.usecase.SignInResult
import com.latenighthack.social.login.v1.LoginServer
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertTrue

// The subject the fake Apple sign-in vouches for. The DevSocialTokenVerifier trusts the token
// verbatim as the subject, so both devices bind/recover under this identity.
private const val APPLE_SUBJECT = "apple-sub-integration"

private val TEST_MASTER_KEY = ByteArray(CustodyCrypto.KEY_BYTES) { (it + 1).toByte() }

private val fakeApple = object : AppleSignInClient {
    override suspend fun signIn(): String = APPLE_SUBJECT
}

// Boots the full lockers monolith (for account create/restore) and the login core service — with only
// the Apple verifier enabled — on one in-process server, so a single rpcClient drives both.
private suspend fun Application.attachLoginAndLockers() {
    attachTestServices()
    val delegate = InMemoryStoreDelegate()
    val credentials = CredentialStore(delegate)
    val challenges = ChallengeStore(delegate)
    credentials.prepare()
    challenges.prepare()
    delegate.createStores()
    val service = LoginServiceImpl(
        credentials = credentials,
        challenges = challenges,
        custody = CustodyCrypto(TEST_MASTER_KEY),
        hasher = Pbkdf2Hasher(iterations = 1000),
        appleVerifier = DevSocialTokenVerifier(),
        googleVerifier = null,
        emailSender = null,
        smsSender = null,
        linkBaseUrl = "https://app.test/login",
    )
    routing { serveAll(service, LoginServer.Descriptor) }
}

// A fresh, started AccountManager over the shared server (no local identity).
private suspend fun bootAccount(rpcClient: RpcClient): AccountManagerImpl {
    val manager = AccountManagerImpl(KeyValueStore(InMemoryKeyValueStoreDelegate()))
    val keySource = AccountKeySource(manager)
    val lockers = LockersClient.create(
        rpcClient = rpcClient,
        storeDelegate = InMemoryStoreDelegate(),
        keyValueStore = KeyValueStore(InMemoryKeyValueStoreDelegate()),
        keySource = keySource,
        appVersion = Version(0, 0, 1),
        lockKeySource = keySource,
    )
    manager.start(lockers)
    return manager
}

class LoginAccountIntegrationTest {

    @Test(timeout = 30_000)
    fun `sign up binds the account, then a new device recovers it via the login method`() =
        runTestWithServer({ attachLoginAndLockers() }) { server, _ ->
            val loginClient = LoginClientImpl(server.rpcClient)

            // Device A: create an account, then sign up with Apple → needs binding → bind it.
            val deviceA = bootAccount(server.rpcClient)
            val accountId = deviceA.createAccount()
            deviceA.lifecycle.first { it is Lifecycle.Ready }

            val signUp = AuthenticateWithAppleUseCase(loginClient, fakeApple, deviceA).authenticate()
            assertTrue(signUp is SignInResult.NeedsBinding, "a first-time method should need binding")

            val bound = BindCurrentAccountUseCase(loginClient, deviceA).bind(signUp.bindTicket)
            assertTrue(bound is BindResult.Bound, "binding the current account should succeed")

            // Device B: no local identity. Sign in with the same Apple identity → recover the key.
            val deviceB = bootAccount(server.rpcClient)
            assertTrue(deviceB.lifecycle.first() is Lifecycle.NoAccount)

            val signIn = AuthenticateWithAppleUseCase(loginClient, fakeApple, deviceB).authenticate()
            assertTrue(signIn is SignInResult.Recovered, "a bound method should recover the account")
            assertTrue(signIn.accountId.contentEquals(accountId), "the recovered account id must match")

            val ready = deviceB.lifecycle.first { it is Lifecycle.Ready } as Lifecycle.Ready
            assertTrue(ready.accountId.contentEquals(accountId))

            deviceA.stop()
            deviceB.stop()
        }
}
