package com.latenighthack.social.login.core.service

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Reversible encryption of the custodial account private key at rest. Because the key must be handed
 * back to the user on a successful login it cannot be one-way hashed — it is encrypted, so a database
 * leak alone (without the master key) does not reveal any account key.
 *
 * Per-record envelope: a fresh 32-byte salt derives a per-record DEK via HKDF-SHA256(master key,
 * salt), and the plaintext is sealed with AES-256-GCM under a fresh 12-byte nonce with AAD binding
 * `provider|subject|key_version|alg` — so a record's ciphertext cannot be decrypted under another
 * subject or transplanted between rows. [keyVersion] names the master key a record is sealed under,
 * enabling rotation by re-wrapping on the next successful use.
 *
 * Legacy records (empty salt) were sealed directly under the master key with no AAD; [decrypt] falls
 * back to that path so existing rows keep working until re-wrapped.
 */
class CustodyCrypto(
    masterKey: ByteArray,
    val keyVersion: Int = CURRENT_KEY_VERSION,
    private val random: SecureRandom = SecureRandom(),
) {
    private val masterKey = masterKey.copyOf()
    private val legacyKey = SecretKeySpec(masterKey.copyOf(), "AES")

    init {
        require(masterKey.size == KEY_BYTES) { "master key must be $KEY_BYTES bytes (AES-256)" }
    }

    /** The ciphertext (GCM tag appended) plus the per-record salt/nonce/version to persist. */
    class Sealed(val ciphertext: ByteArray, val nonce: ByteArray, val salt: ByteArray, val keyVersion: Int)

    /** What the AAD binds a record to: the credential's provider number and subject bytes. */
    class Binding(val provider: Int, val subject: ByteArray)

    /** Encrypt [plaintext] bound to [binding] under a fresh salt-derived DEK. */
    fun encrypt(plaintext: ByteArray, binding: Binding): Sealed {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, deriveDek(salt), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad(binding, keyVersion))
        return Sealed(cipher.doFinal(plaintext), nonce, salt, keyVersion)
    }

    /**
     * Decrypt a record produced by [encrypt] (non-empty [salt]) or by the legacy direct-master-key
     * scheme (empty [salt]). Throws if the tag does not verify or the binding does not match.
     */
    fun decrypt(ciphertext: ByteArray, nonce: ByteArray, salt: ByteArray, recordKeyVersion: Int, binding: Binding): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        return if (salt.isEmpty()) {
            cipher.init(Cipher.DECRYPT_MODE, legacyKey, GCMParameterSpec(TAG_BITS, nonce))
            cipher.doFinal(ciphertext)
        } else {
            cipher.init(Cipher.DECRYPT_MODE, deriveDek(salt), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(aad(binding, recordKeyVersion))
            cipher.doFinal(ciphertext)
        }
    }

    /** True when a record predates the envelope scheme (or an older key version) and should be re-wrapped. */
    fun needsRewrap(salt: ByteArray, recordKeyVersion: Int): Boolean =
        salt.isEmpty() || recordKeyVersion != keyVersion

    // RFC 5869 HKDF-SHA256, extract-then-expand; a single expand block suffices for a 32-byte DEK.
    private fun deriveDek(salt: ByteArray): SecretKeySpec {
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(salt, HMAC))
        val prk = mac.doFinal(masterKey)
        mac.init(SecretKeySpec(prk, HMAC))
        mac.update(HKDF_INFO)
        mac.update(0x01)
        return SecretKeySpec(mac.doFinal(), "AES")
    }

    private fun aad(binding: Binding, version: Int): ByteArray {
        val subject = Base64.getEncoder().encodeToString(binding.subject)
        return "${binding.provider}|$subject|$version|$ALG".encodeToByteArray()
    }

    companion object {
        const val KEY_BYTES = 32
        const val CURRENT_KEY_VERSION = 1
        const val ALG = "HKDF-SHA256+AES-256-GCM/v1"

        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val HMAC = "HmacSHA256"
        private const val SALT_BYTES = 32
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        private val HKDF_INFO = "social.login.custody.dek.v1".encodeToByteArray()
    }
}
