package com.latenighthack.social.login.core.service

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CustodyCryptoTest {

    private val masterKey = ByteArray(CustodyCrypto.KEY_BYTES) { it.toByte() }
    private val binding = CustodyCrypto.Binding(1, "subject-1".encodeToByteArray())

    @Test
    fun `encrypt then decrypt round-trips the plaintext`() {
        val custody = CustodyCrypto(masterKey)
        val plaintext = Random.nextBytes(32)

        val sealed = custody.encrypt(plaintext, binding)
        assertFalse(sealed.ciphertext.contentEquals(plaintext), "ciphertext must not equal plaintext")

        val decrypted = custody.decrypt(sealed.ciphertext, sealed.nonce, sealed.salt, sealed.keyVersion, binding)
        assertTrue(decrypted.contentEquals(plaintext))
    }

    @Test
    fun `each encryption uses a fresh salt and nonce`() {
        val custody = CustodyCrypto(masterKey)
        val plaintext = Random.nextBytes(32)

        val first = custody.encrypt(plaintext, binding)
        val second = custody.encrypt(plaintext, binding)

        assertFalse(first.salt.contentEquals(second.salt), "salts must differ per encryption")
        assertFalse(first.nonce.contentEquals(second.nonce), "nonces must differ per encryption")
        assertFalse(first.ciphertext.contentEquals(second.ciphertext), "ciphertexts must differ per encryption")
    }

    @Test
    fun `decrypting under a different subject fails the AAD binding`() {
        val custody = CustodyCrypto(masterKey)
        val sealed = custody.encrypt(Random.nextBytes(32), binding)
        val other = CustodyCrypto.Binding(1, "subject-2".encodeToByteArray())

        assertFailsWith<Exception> {
            custody.decrypt(sealed.ciphertext, sealed.nonce, sealed.salt, sealed.keyVersion, other)
        }
    }

    @Test
    fun `decrypting under a different provider fails the AAD binding`() {
        val custody = CustodyCrypto(masterKey)
        val sealed = custody.encrypt(Random.nextBytes(32), binding)
        val other = CustodyCrypto.Binding(2, binding.subject)

        assertFailsWith<Exception> {
            custody.decrypt(sealed.ciphertext, sealed.nonce, sealed.salt, sealed.keyVersion, other)
        }
    }

    @Test
    fun `decrypting with the wrong master key fails`() {
        val sealed = CustodyCrypto(masterKey).encrypt(Random.nextBytes(32), binding)
        val other = CustodyCrypto(ByteArray(CustodyCrypto.KEY_BYTES) { (it + 7).toByte() })

        assertFailsWith<Exception> {
            other.decrypt(sealed.ciphertext, sealed.nonce, sealed.salt, sealed.keyVersion, binding)
        }
    }

    @Test
    fun `decrypting with the wrong nonce fails the tag`() {
        val custody = CustodyCrypto(masterKey)
        val sealed = custody.encrypt(Random.nextBytes(32), binding)
        val wrongNonce = sealed.nonce.copyOf().also { it[0] = (it[0] + 1).toByte() }

        assertFailsWith<Exception> {
            custody.decrypt(sealed.ciphertext, wrongNonce, sealed.salt, sealed.keyVersion, binding)
        }
    }

    @Test
    fun `a legacy record with no salt decrypts via the direct master-key path`() {
        // Seal the way the pre-envelope scheme did: AES-GCM directly under the master key, no AAD.
        val plaintext = Random.nextBytes(32)
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(masterKey, "AES"), GCMParameterSpec(128, nonce))
        val legacyCiphertext = cipher.doFinal(plaintext)

        val custody = CustodyCrypto(masterKey)
        val decrypted = custody.decrypt(legacyCiphertext, nonce, ByteArray(0), 0, binding)
        assertTrue(decrypted.contentEquals(plaintext))
        assertTrue(custody.needsRewrap(ByteArray(0), 0), "legacy records must be flagged for re-wrap")
    }

    @Test
    fun `current-envelope records do not need re-wrap`() {
        val custody = CustodyCrypto(masterKey)
        val sealed = custody.encrypt(Random.nextBytes(32), binding)
        assertFalse(custody.needsRewrap(sealed.salt, sealed.keyVersion))
    }

    @Test
    fun `a record sealed under an older key version is flagged for re-wrap`() {
        val old = CustodyCrypto(masterKey, keyVersion = 1)
        val sealed = old.encrypt(Random.nextBytes(32), binding)
        val rotated = CustodyCrypto(masterKey, keyVersion = 2)
        assertTrue(rotated.needsRewrap(sealed.salt, sealed.keyVersion))
    }

    @Test
    fun `a non-256-bit master key is rejected`() {
        assertFailsWith<IllegalArgumentException> { CustodyCrypto(ByteArray(16)) }
    }
}
