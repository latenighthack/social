package com.latenighthack.social.login.core.service

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Reversible encryption of the custodial account private key at rest. The key is sealed with
 * AES-256-GCM under a single service master key (supplied to the service from config); each record
 * gets a fresh random 12-byte nonce, returned alongside the ciphertext for storage. Because the key
 * must be handed back to the user on a successful login it cannot be one-way hashed — it is
 * encrypted, so a database leak alone (without the master key) does not reveal any account key.
 */
class CustodyCrypto(
    masterKey: ByteArray,
    private val random: SecureRandom = SecureRandom(),
) {
    private val key = SecretKeySpec(masterKey.copyOf(), "AES")

    init {
        require(masterKey.size == KEY_BYTES) { "master key must be $KEY_BYTES bytes (AES-256)" }
    }

    /** Encrypt [plaintext]; returns the ciphertext (with the GCM tag appended) and the random nonce. */
    fun encrypt(plaintext: ByteArray): Sealed {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        return Sealed(cipher.doFinal(plaintext), nonce)
    }

    /** Decrypt [ciphertext] produced by [encrypt] under [nonce]. Throws if the tag does not verify. */
    fun decrypt(ciphertext: ByteArray, nonce: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        return cipher.doFinal(ciphertext)
    }

    class Sealed(val ciphertext: ByteArray, val nonce: ByteArray)

    companion object {
        const val KEY_BYTES = 32

        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
    }
}
