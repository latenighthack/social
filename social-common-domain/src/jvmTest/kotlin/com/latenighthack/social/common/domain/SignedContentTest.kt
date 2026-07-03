package com.latenighthack.social.common.domain

import com.latenighthack.ktcrypto.SHA256
import com.latenighthack.ktcrypto.Secp256r1KeyPair
import com.latenighthack.ktcrypto.digest
import com.latenighthack.ktcrypto.encode
import com.latenighthack.ktcrypto.generate
import com.latenighthack.social.common.v1.copy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SignedContentTest {

    private val label = 7L

    @Test
    fun `sign then verify round-trips`() = runTest {
        val key = Secp256r1KeyPair.generate()
        val signed = sign(key, label, "hello".encodeToByteArray())
        assertEquals(1, signed.signatures.size)
        assertTrue(verify(signed, label, key.publicKey))
    }

    @Test
    fun `signer key hash is the truncated sha256 of the public key`() = runTest {
        val key = Secp256r1KeyPair.generate()
        val signed = sign(key, label, "x".encodeToByteArray())
        val expected = SHA256.digest(key.publicKey.encode()).copyOf(SIGNER_KEY_HASH_LENGTH)
        assertEquals(SIGNER_KEY_HASH_LENGTH, signed.signatures.single().signerKeyHash.size)
        assertTrue(signed.signatures.single().signerKeyHash.contentEquals(expected))
    }

    @Test
    fun `verify fails for a different key`() = runTest {
        val key = Secp256r1KeyPair.generate()
        val other = Secp256r1KeyPair.generate()
        val signed = sign(key, label, "x".encodeToByteArray())
        assertFalse(verify(signed, label, other.publicKey))
    }

    @Test
    fun `verify fails when the content is tampered`() = runTest {
        val key = Secp256r1KeyPair.generate()
        val signed = sign(key, label, "x".encodeToByteArray())
        val tampered = signed.copy { content = "y".encodeToByteArray() }
        assertFalse(verify(tampered, label, key.publicKey))
    }

    @Test
    fun `verify fails under a different domain label`() = runTest {
        val key = Secp256r1KeyPair.generate()
        val signed = sign(key, label, "x".encodeToByteArray())
        assertFalse(verify(signed, label + 1, key.publicKey))
    }

    @Test
    fun `verify fails when the signature bytes are corrupted`() = runTest {
        val key = Secp256r1KeyPair.generate()
        val signed = sign(key, label, "x".encodeToByteArray())
        val sig = signed.signatures.single()
        val flipped = sig.signature.copyOf().also { it[0] = (it[0] + 1).toByte() }
        val broken = signed.copy { signatures = listOf(sig.copy(signature = flipped)) }
        assertFalse(verify(broken, label, key.publicKey))
    }
}
