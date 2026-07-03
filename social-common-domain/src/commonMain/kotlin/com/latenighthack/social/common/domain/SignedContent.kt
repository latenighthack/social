package com.latenighthack.social.common.domain

import com.latenighthack.ktcrypto.SHA256
import com.latenighthack.ktcrypto.Secp256r1KeyPair
import com.latenighthack.ktcrypto.Secp256r1PublicKey
import com.latenighthack.ktcrypto.digest
import com.latenighthack.ktcrypto.encode
import com.latenighthack.social.common.v1.SignedContent

/**
 * Public-key-signed bytes. [sign] wraps opaque [content] with one secp256r1 signature over a
 * domain-separated, length-prefixed transcript; [verify] checks a [SignedContent] against the
 * key the caller expects from context. The [SignedContent] wire format carries a repeated
 * signature so signatures can ladder (each over a transcript that folds in the previous one);
 * only the single-signer path is implemented here — a laddered signer is a later addition that
 * needs no wire change.
 */

/** First N bytes of sha256(compressed public key) — a signer identification hint, never authority. */
const val SIGNER_KEY_HASH_LENGTH = 16

/**
 * Sign [content] with [key] under the int64 domain-separation tag [label], returning a single-signer
 * envelope. The signature covers `PAE(label, content, "")`; the previous-signature slot is empty for
 * the first (and here, only) signer.
 */
suspend fun sign(key: Secp256r1KeyPair, label: Long, content: ByteArray): SignedContent {
    val signature = ecdsaDerToRaw(key.privateKey.sign(transcript(label, content, ByteArray(0))))
    val keyHash = SHA256.digest(key.publicKey.encode()).copyOf(SIGNER_KEY_HASH_LENGTH)
    return SignedContent {
        this.content = content
        signatures = listOf(SignedContent.Signature(signerKeyHash = keyHash, signature = signature))
    }
}

/**
 * Verify a single-signer [signed] envelope against [key] under the int64 tag [label]. The
 * `signer_key_hash` is checked against [key] as a consistency guard, but authority comes from the
 * signature verifying against the key the caller resolved from context — not from the hash.
 */
suspend fun verify(signed: SignedContent, label: Long, key: Secp256r1PublicKey): Boolean {
    val signature = signed.signatures.singleOrNull() ?: return false
    val expectedHash = SHA256.digest(key.encode()).copyOf(SIGNER_KEY_HASH_LENGTH)
    if (!signature.signerKeyHash.contentEquals(expectedHash)) return false
    return try {
        key.verify(transcript(label, signed.content, ByteArray(0)), signature.signature)
    } catch (e: Exception) {
        false
    }
}

/**
 * The bytes fed to ECDSA: a length-prefixed "pre-authentication encoding" of the fields, so no
 * two field boundaries can be shifted to collide (DSSE/SIGSTORE-style; mirrors lockers' Writer).
 * The [label] is folded in as an 8-byte big-endian int64; each field is an 8-byte big-endian length
 * followed by its bytes.
 */
private fun transcript(label: Long, content: ByteArray, previousSignature: ByteArray): ByteArray {
    val fields = listOf(bigEndian(label), content, previousSignature)
    var out = ByteArray(0)
    for (field in fields) {
        out = out + bigEndian(field.size.toLong()) + field
    }
    return out
}

/** 8-byte big-endian encoding, used for both field length prefixes and the int64 label. */
private fun bigEndian(value: Long): ByteArray {
    val out = ByteArray(8)
    var v = value
    for (i in 7 downTo 0) {
        out[i] = (v and 0xFF).toByte()
        v = v ushr 8
    }
    return out
}

/**
 * ktcrypto's secp256r1 `sign` emits a DER-encoded ECDSA signature, but its `verify` expects a
 * fixed-width raw r‖s. Convert here so a signature we produce verifies against the on-file key.
 * (The lockers connector has the same helper, but it is `internal` to that module.)
 */
internal fun ecdsaDerToRaw(der: ByteArray): ByteArray {
    var offset = 0
    check(der.getOrNull(offset++) == 0x30.toByte()) { "invalid DER signature header" }
    offset++ // sequence length — always short-form for P-256 signatures
    check(der[offset++] == 0x02.toByte()) { "invalid DER signature (r)" }
    val rLen = der[offset++].toInt() and 0xFF
    val r = der.copyOfRange(offset, offset + rLen)
    offset += rLen
    check(der[offset++] == 0x02.toByte()) { "invalid DER signature (s)" }
    val sLen = der[offset++].toInt() and 0xFF
    val s = der.copyOfRange(offset, offset + sLen)
    return leftPad32(r) + leftPad32(s)
}

private fun leftPad32(value: ByteArray): ByteArray {
    var start = 0
    while (start < value.size - 1 && value[start] == 0.toByte()) start++
    val trimmed = value.copyOfRange(start, value.size)
    val out = ByteArray(32)
    val copyLen = minOf(trimmed.size, 32)
    trimmed.copyInto(out, 32 - copyLen, trimmed.size - copyLen, trimmed.size)
    return out
}
