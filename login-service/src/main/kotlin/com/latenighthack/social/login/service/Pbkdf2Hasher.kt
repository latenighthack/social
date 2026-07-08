package com.latenighthack.social.login.service

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Salted, one-way hashing of authentication secrets (magic-link tokens and OTP codes) with PBKDF2
 * (HMAC-SHA256). Each secret gets a fresh random salt; the salt and iteration count are stored
 * alongside the hash so it can be re-derived on verification. The stored token/code is therefore
 * never recoverable from the database. Low-entropy secrets (OTP codes) additionally rely on short
 * expiry and attempt caps enforced by the service; the KDF cost is a defence-in-depth layer.
 */
class Pbkdf2Hasher(
    val iterations: Int = DEFAULT_ITERATIONS,
    private val random: SecureRandom = SecureRandom(),
) {
    fun hash(secret: String): Hashed {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        return Hashed(derive(secret, salt, iterations), salt)
    }

    /** Constant-time comparison of [secret] against a stored hash under its [salt] and [iterations]. */
    fun verify(secret: String, expectedHash: ByteArray, salt: ByteArray, iterations: Int): Boolean =
        MessageDigest.isEqual(derive(secret, salt, iterations), expectedHash)

    private fun derive(secret: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(secret.toCharArray(), salt, iterations, KEY_BITS)
        return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
    }

    class Hashed(val hash: ByteArray, val salt: ByteArray)

    companion object {
        const val DEFAULT_ITERATIONS = 120_000

        private const val ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val SALT_BYTES = 16
        private const val KEY_BITS = 256
    }
}
