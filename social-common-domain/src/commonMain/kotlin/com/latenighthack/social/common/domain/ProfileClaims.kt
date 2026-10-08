package com.latenighthack.social.common.domain

import com.latenighthack.ktcrypto.Secp256r1PublicKey
import com.latenighthack.ktcrypto.decode
import com.latenighthack.social.common.v1.SignedContent

/** The locker id and room are context, never author identity inferred from a shared room key. */
suspend fun verifyProfileClaim(expectedProfile: ByteArray, expectedRoom: ByteArray,
    claimedProfile: ByteArray, claimedRoom: ByteArray, proof: SignedContent?, label: Long, content: ByteArray): Boolean {
    if (!expectedProfile.contentEquals(claimedProfile) || !expectedRoom.contentEquals(claimedRoom)) return false
    if (proof == null || !proof.content.contentEquals(content)) return false
    return try { verify(proof, label, Secp256r1PublicKey.decode(expectedProfile)) }
    catch (failure: Exception) {
        if (failure is kotlinx.coroutines.CancellationException) throw failure
        false
    }
}
