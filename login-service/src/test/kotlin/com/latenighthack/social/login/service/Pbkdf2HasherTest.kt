package com.latenighthack.social.login.service

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Pbkdf2HasherTest {

    private val hasher = Pbkdf2Hasher(iterations = 1000)

    @Test
    fun `verify accepts the correct secret and rejects a wrong one`() {
        val hashed = hasher.hash("correct-horse")

        assertTrue(hasher.verify("correct-horse", hashed.hash, hashed.salt, hasher.iterations))
        assertFalse(hasher.verify("battery-staple", hashed.hash, hashed.salt, hasher.iterations))
    }

    @Test
    fun `each hash uses a fresh salt so identical secrets hash differently`() {
        val first = hasher.hash("same-secret")
        val second = hasher.hash("same-secret")

        assertFalse(first.salt.contentEquals(second.salt), "salts must differ per hash")
        assertFalse(first.hash.contentEquals(second.hash), "hashes of the same secret must differ under different salts")
    }

    @Test
    fun `verifying under the wrong iteration count fails`() {
        val hashed = hasher.hash("secret")

        assertFalse(hasher.verify("secret", hashed.hash, hashed.salt, hasher.iterations + 1))
    }
}
