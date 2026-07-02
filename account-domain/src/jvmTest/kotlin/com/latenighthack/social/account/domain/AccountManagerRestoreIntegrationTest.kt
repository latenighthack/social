package com.latenighthack.social.account.domain

import com.latenighthack.ktbuf.test.server.runTestWithServer
import com.latenighthack.ktcrypto.Secp256r1KeyPair
import com.latenighthack.ktcrypto.encode
import com.latenighthack.ktcrypto.generate
import com.latenighthack.ktstore.InMemoryKeyValueStoreDelegate
import com.latenighthack.ktstore.InMemoryStoreDelegate
import com.latenighthack.ktstore.KeyValueStore
import com.latenighthack.lockers.common.RoomKeying
import com.latenighthack.lockers.common.v1.Version
import com.latenighthack.lockers.connector.LockersClient
import com.latenighthack.lockers.server.attachTestServices
import com.latenighthack.lockers.server.rpcClient
import com.latenighthack.social.account.domain.AccountManager.Lifecycle
import com.latenighthack.social.account.v1.AccountRecord
import com.latenighthack.social.account.v1.AccountState
import com.latenighthack.social.account.v1.fromByteArray
import com.latenighthack.social.account.v1.toByteArray
import io.ktor.server.application.Application
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AccountManagerRestoreIntegrationTest {

    @Test(timeout = 30_000)
    fun `restoreAccount adopts the key and loads the existing private room`() =
        runTestWithServer(Application::attachTestServices) { server, _ ->
            val keyPair = Secp256r1KeyPair.generate()

            // First device: seed its store with the identity and let it create the private room
            // (and its AccountState) on the shared server.
            val firstStore = KeyValueStore(InMemoryKeyValueStoreDelegate())
            firstStore.save(
                AccountManagerImpl.ACCOUNT_RECORD_KEY,
                AccountRecord {
                    privateKey = keyPair.privateKey.encode()
                    createdAtMillis = 0L
                },
                AccountRecord::toByteArray,
            )
            val first = AccountManagerImpl(firstStore)
            val firstKeySource = AccountKeySource(first)
            val firstLockers = LockersClient.create(
                rpcClient = server.rpcClient,
                storeDelegate = InMemoryStoreDelegate(),
                keyValueStore = KeyValueStore(InMemoryKeyValueStoreDelegate()),
                keySource = firstKeySource,
                appVersion = Version(0, 0, 1),
                lockKeySource = firstKeySource,
            )
            first.start(firstLockers)
            first.lifecycle.first { it is Lifecycle.Ready }

            // Second device: no local identity. Restore from the same private key.
            val second = AccountManagerImpl(KeyValueStore(InMemoryKeyValueStoreDelegate()))
            val secondKeySource = AccountKeySource(second)
            val secondLockers = LockersClient.create(
                rpcClient = server.rpcClient,
                storeDelegate = InMemoryStoreDelegate(),
                keyValueStore = KeyValueStore(InMemoryKeyValueStoreDelegate()),
                keySource = secondKeySource,
                appVersion = Version(0, 0, 1),
                lockKeySource = secondKeySource,
            )
            second.start(secondLockers)
            assertTrue(second.lifecycle.first() is Lifecycle.NoAccount)

            val accountId = second.restoreAccount(keyPair.privateKey.encode())
            assertTrue(accountId.contentEquals(keyPair.publicKey.encode()))

            val ready = second.lifecycle.first { it is Lifecycle.Ready } as Lifecycle.Ready
            assertEquals(RoomKeying.publicKeyed(accountId), ready.privateRoom)

            // The restored device sees the existing AccountState in the private room.
            val stateClient = secondLockers.typed(
                AccountKeyspaces.ACCOUNT_STATE, AccountState::toByteArray, AccountState.Companion::fromByteArray,
            )
            assertNotNull(stateClient.getLocker(ready.privateRoom, AccountKeyspaces.ACCOUNT_STATE_LOCKER))
            assertTrue(second.hasSessionKey())

            second.stop()
            first.stop()
            secondLockers.close()
            firstLockers.close()
        }

    @Test(timeout = 30_000)
    fun `restoreAccount fails and persists nothing when the room has no account state`() =
        runTestWithServer(Application::attachTestServices) { server, _ ->
            // A key that was never used to create an account — its private room is empty.
            val keyPair = Secp256r1KeyPair.generate()

            val manager = AccountManagerImpl(KeyValueStore(InMemoryKeyValueStoreDelegate()))
            val keySource = AccountKeySource(manager)
            val lockers = LockersClient.create(
                rpcClient = server.rpcClient,
                storeDelegate = InMemoryStoreDelegate(),
                keyValueStore = KeyValueStore(InMemoryKeyValueStoreDelegate()),
                keySource = keySource,
                appVersion = Version(0, 0, 1),
                lockKeySource = keySource,
            )
            manager.start(lockers)

            assertFailsWith<NoAccountToRestoreException> {
                manager.restoreAccount(keyPair.privateKey.encode())
            }

            // Nothing was persisted and the lifecycle stayed at NoAccount.
            assertFalse(manager.hasSessionKey())
            assertTrue(manager.lifecycle.first() is Lifecycle.NoAccount)

            manager.stop()
            lockers.close()
        }
}
