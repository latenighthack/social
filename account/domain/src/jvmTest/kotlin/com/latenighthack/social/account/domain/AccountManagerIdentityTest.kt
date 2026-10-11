package com.latenighthack.social.account.domain

import com.latenighthack.ktcrypto.encode
import com.latenighthack.ktstore.InMemoryKeyValueStoreDelegate
import com.latenighthack.ktstore.KeyValueStore
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Identity persistence — key material is owned by the manager, no client needed. */
class AccountManagerIdentityTest {

    @Test
    fun `key persists and reloads across managers over the same store`() = runTest {
        val store = KeyValueStore(InMemoryKeyValueStoreDelegate())

        val first = AccountManagerImpl(store)
        assertFalse(first.hasSessionKey())
        val accountId = first.createAccount()
        assertEquals(33, accountId.size)
        assertTrue(first.hasSessionKey())

        // A fresh manager over the same store reloads the same key.
        val reloaded = AccountManagerImpl(store)
        assertTrue(reloaded.hasSessionKey())
        assertTrue(reloaded.sessionKeyPair().publicKey.encode().contentEquals(accountId))
    }

    @Test
    fun `sessionKeyPair resolves once the account is created`() = runTest {
        val manager = AccountManagerImpl(KeyValueStore(InMemoryKeyValueStoreDelegate()))

        val awaiting = async { manager.sessionKeyPair() }
        manager.createAccount()
        assertNotNull(awaiting.await())
    }
}
