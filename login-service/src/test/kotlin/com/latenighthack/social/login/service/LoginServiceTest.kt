package com.latenighthack.social.login.service

import com.latenighthack.ktbuf.server.serveAll
import com.latenighthack.ktbuf.test.server.runTestWithServer
import com.latenighthack.ktstore.InMemoryStoreDelegate
import com.latenighthack.lockers.server.rpcClient
import com.latenighthack.social.login.v1.AuthenticateSocialRequest
import com.latenighthack.social.login.v1.BindRequest
import com.latenighthack.social.login.v1.CompleteEmailLinkRequest
import com.latenighthack.social.login.v1.LocalLoginServiceRpc
import com.latenighthack.social.login.v1.LoginResult
import com.latenighthack.social.login.v1.LoginServer
import com.latenighthack.social.login.v1.LoginServiceRpc
import com.latenighthack.social.login.v1.Provider
import com.latenighthack.social.login.v1.StartEmailLinkRequest
import com.latenighthack.social.login.v1.StartPhoneCodeRequest
import com.latenighthack.social.login.v1.VerifyPhoneCodeRequest
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import java.net.URLDecoder
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

private val TEST_MASTER_KEY = ByteArray(CustodyCrypto.KEY_BYTES) { (it * 7).toByte() }

private class CapturingEmailSender : EmailSender {
    var lastLink: String? = null
    override suspend fun sendMagicLink(email: String, link: String) {
        lastLink = link
    }
}

private class CapturingSmsSender : SmsSender {
    var lastCode: String? = null
    override suspend fun sendCode(phoneNumber: String, code: String) {
        lastCode = code
    }
}

private class MutableClock(var millis: Long) {
    fun get(): Long = millis
}

private fun tokenFromLink(link: String): String =
    URLDecoder.decode(link.substringAfter("token="), "UTF-8")

private suspend fun Application.attachLogin(
    email: EmailSender = ConsoleEmailSender(),
    sms: SmsSender = ConsoleSmsSender(),
    apple: SocialTokenVerifier = DevSocialTokenVerifier(),
    google: SocialTokenVerifier = DevSocialTokenVerifier(),
    clock: () -> Long = System::currentTimeMillis,
    maxAttempts: Int = 5,
) {
    val delegate = InMemoryStoreDelegate()
    // Stores share one delegate; prepare each, then create the tables once.
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
        emailSender = email,
        smsSender = sms,
        appleVerifier = apple,
        googleVerifier = google,
        linkBaseUrl = "https://app.test/login",
        clock = clock,
        maxAttempts = maxAttempts,
    )
    routing { serveAll(service, LoginServer.Descriptor) }
}

class LoginServiceTest {

    @Test
    fun `social new user gets a bind ticket, binds, then recovers the same key`() =
        runTestWithServer({ attachLogin() }) { server, _ ->
            val rpc = LoginServiceRpc(server.rpcClient)
            val accountId = Random.nextBytes(33)
            val privateKey = Random.nextBytes(32)

            val first = rpc.authenticateSocial(
                AuthenticateSocialRequest { provider = Provider.PROVIDER_APPLE; idToken = "apple-sub-alice" },
            )
            assertEquals(LoginResult.LOGIN_RESULT_NEEDS_BINDING, first.result)
            assertTrue(first.bindTicket.isNotEmpty())

            val bind = rpc.bind(
                BindRequest {
                    bindTicket = first.bindTicket
                    this.accountId = accountId
                    accountPrivateKey = privateKey
                },
            )
            assertEquals(LoginResult.LOGIN_RESULT_OK, bind.result)

            val recover = rpc.authenticateSocial(
                AuthenticateSocialRequest { provider = Provider.PROVIDER_APPLE; idToken = "apple-sub-alice" },
            )
            assertEquals(LoginResult.LOGIN_RESULT_OK, recover.result)
            val identity = assertNotNull(recover.identity)
            assertTrue(identity.accountId.contentEquals(accountId))
            assertTrue(identity.accountPrivateKey.contentEquals(privateKey))
        }

    @Test
    fun `email magic link round trip yields a bind ticket for a new user`() {
        val email = CapturingEmailSender()
        runTestWithServer({ attachLogin(email = email) }) { server, _ ->
            val rpc = LoginServiceRpc(server.rpcClient)
            val start = rpc.startEmailLink(StartEmailLinkRequest { this.email = "alice@example.com" })
            assertEquals(LoginResult.LOGIN_RESULT_OK, start.result)

            val token = tokenFromLink(assertNotNull(email.lastLink))
            val complete = rpc.completeEmailLink(
                CompleteEmailLinkRequest {
                    this.email = "alice@example.com"
                    this.token = token
                },
            )
            assertEquals(LoginResult.LOGIN_RESULT_NEEDS_BINDING, complete.result)
            assertTrue(complete.bindTicket.isNotEmpty())
        }
    }

    @Test
    fun `wrong OTP codes are rejected and then exhausted`() {
        val sms = CapturingSmsSender()
        runTestWithServer({ attachLogin(sms = sms, maxAttempts = 3) }) { server, _ ->
            val rpc = LoginServiceRpc(server.rpcClient)
            rpc.startPhoneCode(StartPhoneCodeRequest { phoneNumber = "+15551230000" })
            assertNotNull(sms.lastCode)

            repeat(2) {
                val rejected = rpc.verifyPhoneCode(
                    VerifyPhoneCodeRequest { phoneNumber = "+15551230000"; code = "000000-wrong" },
                )
                assertEquals(LoginResult.LOGIN_RESULT_INVALID, rejected.result)
            }
            val exhausted = rpc.verifyPhoneCode(
                VerifyPhoneCodeRequest { phoneNumber = "+15551230000"; code = "000000-wrong" },
            )
            assertEquals(LoginResult.LOGIN_RESULT_EXHAUSTED, exhausted.result)
        }
    }

    @Test
    fun `phone OTP round trip verifies the delivered code`() {
        val sms = CapturingSmsSender()
        runTestWithServer({ attachLogin(sms = sms) }) { server, _ ->
            val rpc = LoginServiceRpc(server.rpcClient)
            rpc.startPhoneCode(StartPhoneCodeRequest { phoneNumber = "+15551239999" })
            val code = assertNotNull(sms.lastCode)

            val verified = rpc.verifyPhoneCode(
                VerifyPhoneCodeRequest { phoneNumber = "+15551239999"; this.code = code },
            )
            assertEquals(LoginResult.LOGIN_RESULT_NEEDS_BINDING, verified.result)
        }
    }

    @Test
    fun `an expired email challenge is rejected`() {
        val email = CapturingEmailSender()
        val clock = MutableClock(1_000L)
        runTestWithServer({ attachLogin(email = email, clock = clock::get) }) { server, _ ->
            val rpc = LoginServiceRpc(server.rpcClient)
            rpc.startEmailLink(StartEmailLinkRequest { this.email = "bob@example.com" })
            val token = tokenFromLink(assertNotNull(email.lastLink))

            clock.millis = 1_000L + 16 * 60 * 1000L // past the 15-minute TTL
            val complete = rpc.completeEmailLink(
                CompleteEmailLinkRequest { this.email = "bob@example.com"; this.token = token },
            )
            assertEquals(LoginResult.LOGIN_RESULT_EXPIRED, complete.result)
        }
    }

    @Test
    fun `binding a second account to the same identity is rejected`() =
        runTestWithServer({ attachLogin() }) { server, _ ->
            val rpc = LoginServiceRpc(server.rpcClient)
            val ticketOne = rpc.authenticateSocial(
                AuthenticateSocialRequest { provider = Provider.PROVIDER_GOOGLE; idToken = "google-sub-bob" },
            ).bindTicket
            val ticketTwo = rpc.authenticateSocial(
                AuthenticateSocialRequest { provider = Provider.PROVIDER_GOOGLE; idToken = "google-sub-bob" },
            ).bindTicket

            val bindFirst = rpc.bind(
                BindRequest { bindTicket = ticketOne; accountId = Random.nextBytes(33); accountPrivateKey = Random.nextBytes(32) },
            )
            assertEquals(LoginResult.LOGIN_RESULT_OK, bindFirst.result)

            val bindSecond = rpc.bind(
                BindRequest { bindTicket = ticketTwo; accountId = Random.nextBytes(33); accountPrivateKey = Random.nextBytes(32) },
            )
            assertEquals(LoginResult.LOGIN_RESULT_ALREADY_BOUND, bindSecond.result)
        }

    @Test
    fun `a spent bind ticket cannot be reused`() =
        runTestWithServer({ attachLogin() }) { server, _ ->
            val rpc = LoginServiceRpc(server.rpcClient)
            val ticket = rpc.authenticateSocial(
                AuthenticateSocialRequest { provider = Provider.PROVIDER_APPLE; idToken = "apple-sub-dave" },
            ).bindTicket

            val firstBind = rpc.bind(
                BindRequest { bindTicket = ticket; accountId = Random.nextBytes(33); accountPrivateKey = Random.nextBytes(32) },
            )
            assertEquals(LoginResult.LOGIN_RESULT_OK, firstBind.result)

            val reuse = rpc.bind(
                BindRequest { bindTicket = ticket; accountId = Random.nextBytes(33); accountPrivateKey = Random.nextBytes(32) },
            )
            assertEquals(LoginResult.LOGIN_RESULT_INVALID, reuse.result)
        }

    @Test
    fun `the account key is stored encrypted, not in the clear`() = runTest {
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
            emailSender = ConsoleEmailSender(),
            smsSender = ConsoleSmsSender(),
            appleVerifier = DevSocialTokenVerifier(),
            googleVerifier = DevSocialTokenVerifier(),
            linkBaseUrl = "https://app.test/login",
        )
        val rpc = LocalLoginServiceRpc(service)

        val privateKey = Random.nextBytes(32)
        val auth = rpc.authenticateSocial(
            AuthenticateSocialRequest { provider = Provider.PROVIDER_APPLE; idToken = "carol" },
        )
        rpc.bind(
            BindRequest { bindTicket = auth.bindTicket; accountId = Random.nextBytes(33); accountPrivateKey = privateKey },
        )

        val appleProvider: Provider = Provider.PROVIDER_APPLE
        val lookup = byteArrayOf(appleProvider.value.toByte()) + "carol".encodeToByteArray()
        val record = assertNotNull(credentials.getByLookup(lookup))
        assertFalse(record.encPrivateKey.contentEquals(privateKey), "the key must be ciphertext at rest")
        assertTrue(record.encPrivateKey.isNotEmpty())
    }
}
