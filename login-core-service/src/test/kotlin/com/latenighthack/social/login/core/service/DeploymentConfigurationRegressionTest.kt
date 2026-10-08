package com.latenighthack.social.login.core.service

import java.util.Base64
import kotlin.test.*

class DeploymentConfigurationRegressionTest {
    @Test fun `production refuses ephemeral keys and nonce bypass`() {
        assertFailsWith<IllegalArgumentException> { LoginConfig.fromEnv { null } }
        val encoded = Base64.getEncoder().encodeToString(ByteArray(32))
        assertFailsWith<IllegalArgumentException> { LoginConfig.fromEnv { key ->
            when (key) { "LOGIN_MASTER_KEY" -> encoded; "LOGIN_REQUIRE_NONCE" -> "false"; else -> null }
        } }
        assertEquals(32, LoginConfig.fromEnv { if (it == "LOGIN_DEVELOPMENT_MODE") "true" else null }.masterKey.size)
    }

    @Test fun `rotation decrypts an old envelope and rewraps under a distinct key`() {
        val previous = ByteArray(32) { 1 }
        val next = ByteArray(32) { 2 }
        val binding = CustodyCrypto.Binding(1, byteArrayOf(3))
        val plain = ByteArray(32) { 9 }
        val old = CustodyCrypto(previous).encrypt(plain, binding)
        val rotated = CustodyCrypto(next, keyVersion = 2, previousKeys = mapOf(1 to previous))
        val recovered = rotated.decrypt(old.ciphertext, old.nonce, old.salt, old.keyVersion, binding)
        assertContentEquals(plain, recovered)
        val wrapped = rotated.encrypt(recovered, binding)
        assertContentEquals(plain, CustodyCrypto(next, keyVersion = 2).decrypt(wrapped.ciphertext, wrapped.nonce, wrapped.salt, wrapped.keyVersion, binding))
        assertFailsWith<IllegalArgumentException> { CustodyCrypto(next, keyVersion = 2).decrypt(old.ciphertext, old.nonce, old.salt, old.keyVersion, binding) }
    }
}
