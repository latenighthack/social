package com.latenighthack.social.login.service

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CustodyCryptoTest {

    private val masterKey = ByteArray(CustodyCrypto.KEY_BYTES) { it.toByte() }

    @Test
    fun `encrypt then decrypt round-trips the plaintext`() {
        val custody = CustodyCrypto(masterKey)
        val plaintext = Random.nextBytes(32)

        val sealed = custody.encrypt(plaintext)
        assertFalse(sealed.ciphertext.contentEquals(plaintext), "ciphertext must not equal plaintext")

        val decrypted = custody.decrypt(sealed.ciphertext, sealed.nonce)
        assertTrue(decrypted.contentEquals(plaintext))
    }

    @Test
    fun `each encryption uses a fresh nonce`() {
        val custody = CustodyCrypto(masterKey)
        val plaintext = Random.nextBytes(32)

        val first = custody.encrypt(plaintext)
        val second = custody.encrypt(plaintext)

        assertFalse(first.nonce.contentEquals(second.nonce), "nonces must differ per encryption")
        assertFalse(first.ciphertext.contentEquals(second.ciphertext), "ciphertexts must differ per encryption")
    }

    @Test
    fun `decrypting with the wrong nonce fails the tag`() {
        val custody = CustodyCrypto(masterKey)
        val sealed = custody.encrypt(Random.nextBytes(32))
        val wrongNonce = sealed.nonce.copyOf().also { it[0] = (it[0] + 1).toByte() }

        assertFailsWith<Exception> { custody.decrypt(sealed.ciphertext, wrongNonce) }
    }

    @Test
    fun `a non-256-bit master key is rejected`() {
        assertFailsWith<IllegalArgumentException> { CustodyCrypto(ByteArray(16)) }
    }
}
