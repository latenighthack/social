package com.latenighthack.social.common.domain

import com.latenighthack.ktcrypto.AES
import com.latenighthack.ktcrypto.AESSymmetricKey
import com.latenighthack.ktcrypto.ECDH
import com.latenighthack.ktcrypto.SHA256
import com.latenighthack.ktcrypto.Secp256r1
import com.latenighthack.ktcrypto.Secp256r1KeyPair
import com.latenighthack.ktcrypto.Secp256r1PublicKey
import com.latenighthack.ktcrypto.decode
import com.latenighthack.ktcrypto.decodeKey
import com.latenighthack.ktcrypto.digest
import com.latenighthack.ktcrypto.encode
import com.latenighthack.ktcrypto.encodePublic
import com.latenighthack.ktcrypto.generate
import com.latenighthack.ktcrypto.generateKey
import com.latenighthack.social.common.v1.SealedEnvelope

/**
 * ECIES-style sealing to a recipient's public key. A fresh AES-256 content key encrypts the
 * payload; the content key is wrapped under an AES key derived from `sha256(ECDH(ephemeral,
 * recipient))`. Only the recipient's private key reproduces the ECDH secret, so only they can
 * unwrap. The lockers server does not gate reads, so this — not any room lock — provides secrecy
 * for material that must stay secret, such as the key carried in a room invite.
 */
object Sealing {
    private const val CONTENT_KEY_BITS = 256

    /** Seal [payload] to [recipientPublicKey] (a 33-byte compressed secp256r1 public key). */
    suspend fun seal(recipientPublicKey: ByteArray, payload: ByteArray): SealedEnvelope {
        val ephemeral = Secp256r1KeyPair.generate()
        val recipient = Secp256r1PublicKey.decode(recipientPublicKey)
        val kek = AESSymmetricKey.decodeKey(SHA256.digest(Secp256r1.ECDH.sharedSecret(ephemeral.privateKey, recipient)))
        val contentKey = AES.generateKey(CONTENT_KEY_BITS)
        return SealedEnvelope(
            ephemeralPublicKey = ephemeral.publicKey.encode(),
            wrappedKey = AES.GCM.encrypt(kek, contentKey.encodePublic()),
            ciphertext = AES.GCM.encrypt(contentKey, payload),
        )
    }

    /**
     * Unseal [envelope] given the recipient side's ECDH secret with the envelope's ephemeral key
     * (produced by the key that owns the recipient side). Returns null if the envelope is not for us.
     */
    suspend fun unsealWith(sharedSecret: ByteArray, envelope: SealedEnvelope): ByteArray? =
        try {
            val kek = AESSymmetricKey.decodeKey(SHA256.digest(sharedSecret))
            val contentKey = AESSymmetricKey.decodeKey(AES.GCM.decrypt(kek, envelope.wrappedKey))
            AES.GCM.decrypt(contentKey, envelope.ciphertext)
        } catch (e: Exception) {
            null
        }
}
