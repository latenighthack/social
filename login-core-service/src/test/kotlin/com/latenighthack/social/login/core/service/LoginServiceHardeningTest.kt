package com.latenighthack.social.login.core.service

import com.latenighthack.ktstore.InMemoryStoreDelegate
import com.latenighthack.social.login.v1.AuthenticateSocialRequest
import com.latenighthack.social.login.v1.BindRequest
import com.latenighthack.social.login.v1.CredentialRecord
import com.latenighthack.social.login.v1.LocalLoginServiceRpc
import com.latenighthack.social.login.v1.LoginResult
import com.latenighthack.social.login.v1.Provider
import com.latenighthack.social.login.v1.RequestNonceRequest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

private val MASTER_KEY = ByteArray(CustodyCrypto.KEY_BYTES) { (it * 3).toByte() }
private val APPLE: Provider = Provider.PROVIDER_APPLE

/** Echoes the token as the subject and reports a fixed nonce claim + profile claims. */
private class ClaimsVerifier(
    private val nonceClaim: String? = null,
    private val displayName: String? = null,
    private val photoUrl: String? = null,
    private val email: String? = null,
) : SocialTokenVerifier {
    override suspend fun verify(idToken: String): VerifiedClaims? =
        idToken.ifBlank { null }?.let {
            VerifiedClaims(subject = it, nonce = nonceClaim, displayName = displayName, photoUrl = photoUrl, email = email)
        }
}

private class Harness(
    verifier: SocialTokenVerifier,
    requireNonce: Boolean = false,
    val custody: CustodyCrypto = CustodyCrypto(MASTER_KEY),
) {
    val delegate = InMemoryStoreDelegate()
    val credentials = CredentialStore(delegate)
    val challenges = ChallengeStore(delegate)
    val nonces = NonceService()
    val service = LoginServiceImpl(
        credentials = credentials,
        challenges = challenges,
        custody = custody,
        hasher = Pbkdf2Hasher(iterations = 1000),
        appleVerifier = verifier,
        googleVerifier = verifier,
        emailSender = null,
        smsSender = null,
        linkBaseUrl = "https://app.test/login",
        nonces = nonces,
        requireNonce = requireNonce,
    )
    val rpc = LocalLoginServiceRpc(service)

    suspend fun prepare(): Harness {
        credentials.prepare()
        challenges.prepare()
        delegate.createStores()
        return this
    }
}

class LoginServiceHardeningTest {

    @Test
    fun `a valid nonce round-trips and is single-use`() = runTest {
        // The verifier reports the raw nonce as the token's claim (the Google shape).
        lateinit var issued: String
        val h = Harness(object : SocialTokenVerifier {
            override suspend fun verify(idToken: String): VerifiedClaims? =
                VerifiedClaims(subject = idToken, nonce = issued)
        }, requireNonce = true).prepare()

        issued = h.rpc.requestNonce(RequestNonceRequest {}).nonce
        assertTrue(issued.isNotEmpty())

        val first = h.rpc.authenticateSocial(
            AuthenticateSocialRequest { provider = Provider.PROVIDER_APPLE; idToken = "alice"; nonce = issued },
        )
        assertEquals(LoginResult.LOGIN_RESULT_NEEDS_BINDING, first.result)

        // Replay of the same nonce is rejected even with a valid token.
        val replay = h.rpc.authenticateSocial(
            AuthenticateSocialRequest { provider = Provider.PROVIDER_APPLE; idToken = "alice"; nonce = issued },
        )
        assertEquals(LoginResult.LOGIN_RESULT_UNAUTHORIZED, replay.result)
    }

    @Test
    fun `enforcement rejects a missing nonce`() = runTest {
        val h = Harness(ClaimsVerifier(), requireNonce = true).prepare()
        val response = h.rpc.authenticateSocial(
            AuthenticateSocialRequest { provider = Provider.PROVIDER_APPLE; idToken = "alice" },
        )
        assertEquals(LoginResult.LOGIN_RESULT_UNAUTHORIZED, response.result)
    }

    @Test
    fun `a nonce that does not match the token claim is rejected`() = runTest {
        val h = Harness(ClaimsVerifier(nonceClaim = "some-other-value"), requireNonce = true).prepare()
        val issued = h.rpc.requestNonce(RequestNonceRequest {}).nonce
        val response = h.rpc.authenticateSocial(
            AuthenticateSocialRequest { provider = Provider.PROVIDER_APPLE; idToken = "alice"; nonce = issued },
        )
        assertEquals(LoginResult.LOGIN_RESULT_UNAUTHORIZED, response.result)
    }

    @Test
    fun `without enforcement a legacy client with no nonce still authenticates`() = runTest {
        val h = Harness(ClaimsVerifier(), requireNonce = false).prepare()
        val response = h.rpc.authenticateSocial(
            AuthenticateSocialRequest { provider = Provider.PROVIDER_APPLE; idToken = "alice" },
        )
        assertEquals(LoginResult.LOGIN_RESULT_NEEDS_BINDING, response.result)
    }

    @Test
    fun `without enforcement an unverifiable nonce is tolerated but still spent`() = runTest {
        // The verifier surfaces no nonce claim (the dev-verifier shape) — the flow must not fail,
        // and enforcement (if later enabled) would see the nonce as already consumed.
        val h = Harness(ClaimsVerifier(nonceClaim = null), requireNonce = false).prepare()
        val issued = h.rpc.requestNonce(RequestNonceRequest {}).nonce
        val response = h.rpc.authenticateSocial(
            AuthenticateSocialRequest { provider = Provider.PROVIDER_APPLE; idToken = "alice"; nonce = issued },
        )
        assertEquals(LoginResult.LOGIN_RESULT_NEEDS_BINDING, response.result)
        assertFalse(h.nonces.consume(issued, issued), "the nonce must be spent even on a tolerated mismatch")
    }

    @Test
    fun `verified token claims come back as profile prefills`() = runTest {
        val h = Harness(
            ClaimsVerifier(displayName = "Ada Lovelace", photoUrl = "https://p.test/ada.png", email = "ada@test"),
        ).prepare()
        val response = h.rpc.authenticateSocial(
            AuthenticateSocialRequest { provider = Provider.PROVIDER_GOOGLE; idToken = "ada" },
        )
        assertEquals(LoginResult.LOGIN_RESULT_NEEDS_BINDING, response.result)
        val prefill = assertNotNull(response.prefill)
        assertEquals("Ada Lovelace", prefill.displayName)
        assertEquals("https://p.test/ada.png", prefill.photoUrl)
        assertEquals("ada@test", prefill.email)
    }

    @Test
    fun `bound records carry the envelope salt and key version`() = runTest {
        val h = Harness(ClaimsVerifier()).prepare()
        val auth = h.rpc.authenticateSocial(
            AuthenticateSocialRequest { provider = Provider.PROVIDER_APPLE; idToken = "bob" },
        )
        h.rpc.bind(
            BindRequest { bindTicket = auth.bindTicket; accountId = Random.nextBytes(33); accountPrivateKey = Random.nextBytes(32) },
        )
        val lookup = byteArrayOf(APPLE.value.toByte()) + "bob".encodeToByteArray()
        val record = assertNotNull(h.credentials.getByLookup(lookup))
        assertTrue(record.kdfSalt.isNotEmpty(), "envelope salt must be persisted")
        assertEquals(CustodyCrypto.CURRENT_KEY_VERSION, record.keyVersion)
    }

    @Test
    fun `a legacy record recovers and is re-wrapped to the envelope`() = runTest {
        val h = Harness(ClaimsVerifier()).prepare()
        val accountKey = Random.nextBytes(32)
        val accountId = Random.nextBytes(33)
        val subject = "carol".encodeToByteArray()
        val lookup = byteArrayOf(APPLE.value.toByte()) + subject

        // Seed a record sealed the pre-envelope way: AES-GCM directly under the master key, no AAD.
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(MASTER_KEY, "AES"), GCMParameterSpec(128, nonce))
        h.credentials.put(
            CredentialRecord {
                lookupKey = lookup
                provider = APPLE.value
                this.subject = subject
                this.accountId = accountId
                encPrivateKey = cipher.doFinal(accountKey)
                encNonce = nonce
            },
        )

        val response = h.rpc.authenticateSocial(
            AuthenticateSocialRequest { provider = Provider.PROVIDER_APPLE; idToken = "carol" },
        )
        assertEquals(LoginResult.LOGIN_RESULT_OK, response.result)
        assertTrue(assertNotNull(response.identity).accountPrivateKey.contentEquals(accountKey))

        // The stored record is now sealed under the envelope scheme.
        val rewrapped = assertNotNull(h.credentials.getByLookup(lookup))
        assertTrue(rewrapped.kdfSalt.isNotEmpty(), "legacy record must be re-wrapped after use")
        assertEquals(CustodyCrypto.CURRENT_KEY_VERSION, rewrapped.keyVersion)
    }
}
