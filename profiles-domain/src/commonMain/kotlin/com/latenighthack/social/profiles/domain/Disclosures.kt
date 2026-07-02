package com.latenighthack.social.profiles.domain

import com.latenighthack.ktcrypto.SHA256
import com.latenighthack.ktcrypto.Secp256r1KeyPair
import com.latenighthack.ktcrypto.digest
import com.latenighthack.social.profiles.v1.Profile
import com.latenighthack.social.profiles.v1.Profile.Disclosure
import com.latenighthack.social.profiles.v1.toByteArray

/**
 * Builds the signed disclosures that make up a [Profile]. Each disclosure is signed by the
 * profile key over a truncated sha256 of the disclosed element. Verification is deferred.
 */
internal object Disclosures {
    /** First N bytes of sha256 — the integrity check the signature commits to. */
    const val TRUNCATED_HASH_LENGTH = 16

    suspend fun signDisplayName(profileKey: Secp256r1KeyPair, name: String): Disclosure {
        val displayName = Profile.Disclosure.DisplayName(value = name)
        val digest = SHA256.digest(displayName.toByteArray()).copyOf(TRUNCATED_HASH_LENGTH)
        val signature = ecdsaDerToRaw(profileKey.privateKey.sign(digest))
        return Profile.Disclosure(
            element = Profile.Disclosure.OneOfElement.displayName(displayName),
            signature = signature,
        )
    }
}

/**
 * ktcrypto's secp256r1 `sign` emits a DER-encoded ECDSA signature, but its `verify` expects
 * a fixed-width raw r‖s. Convert here so a future verifier can check these disclosures.
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
