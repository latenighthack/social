package com.latenighthack.social.common.domain

import com.latenighthack.ktcrypto.Secp256r1KeyPair
import com.latenighthack.ktcrypto.generate
import com.latenighthack.ktcrypto.encode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class ProfileClaimsTest {
    @Test fun sharedRoomWritersCannotForgeAnotherProfileOrReplayClaims() = runTest {
        val alice = Secp256r1KeyPair.generate()
        val bob = Secp256r1KeyPair.generate()
        val profile = alice.publicKey.encode()
        val room = byteArrayOf(1)
        val payload = byteArrayOf(1, 2, 3)
        val proof = sign(alice, 4, payload)
        assertTrue(verifyProfileClaim(profile, room, profile, room, proof, 4, payload))
        assertFalse(verifyProfileClaim(profile, room, profile, room, sign(bob, 4, payload), 4, payload))
        assertFalse(verifyProfileClaim(profile, byteArrayOf(2), profile, room, proof, 4, payload))
        assertFalse(verifyProfileClaim(profile, room, profile, room, proof, 5, payload))
        assertFalse(verifyProfileClaim(profile, room, profile, room, null, 4, payload))
    }
}
