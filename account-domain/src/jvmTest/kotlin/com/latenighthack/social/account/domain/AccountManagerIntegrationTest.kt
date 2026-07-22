package com.latenighthack.social.account.domain

import com.latenighthack.ktbuf.net.RpcClient
import com.latenighthack.ktbuf.net.RpcMethodSpecifier
import com.latenighthack.ktbuf.net.RpcResponse
import com.latenighthack.ktbuf.net.RpcResponseException
import com.latenighthack.ktbuf.net.RpcServerStream
import com.latenighthack.ktbuf.proto.Codes
import com.latenighthack.ktbuf.test.server.runTestWithServer
import com.latenighthack.ktstore.InMemoryKeyValueStoreDelegate
import com.latenighthack.ktstore.InMemoryStoreDelegate
import com.latenighthack.ktstore.KeyValueStore
import com.latenighthack.lockers.common.v1.Version
import com.latenighthack.lockers.connector.LockerWriteException
import com.latenighthack.lockers.connector.LockersClient
import com.latenighthack.lockers.server.attachTestServices
import com.latenighthack.lockers.server.rpcClient
import com.latenighthack.social.account.domain.AccountManager.Lifecycle
import com.latenighthack.social.account.v1.AccountState
import com.latenighthack.social.account.v1.copy
import com.latenighthack.social.account.v1.fromByteArray
import com.latenighthack.social.account.v1.toByteArray
import io.ktor.server.application.Application
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AccountManagerIntegrationTest {

    /** Fails every RPC to simulate a fully offline network. */
    private class OfflineRpcClient : RpcClient {
        override suspend fun unaryCall(
            method: RpcMethodSpecifier,
            headers: Map<String, String>,
            request: ByteArray,
        ): RpcResponse = throw RpcResponseException(path = "test", verb = "POST", code = Codes.UNAVAILABLE, errorMessage = "offline")

        override suspend fun serverStreamingCall(
            method: RpcMethodSpecifier,
            block: suspend RpcServerStream.() -> Unit,
            readyCallback: () -> Unit,
        ): Unit = throw RpcResponseException(path = "test", verb = "POST", code = Codes.UNAVAILABLE, errorMessage = "offline")
    }

    @Test(timeout = 30_000)
    fun `a present key reaches Ready without a connection`() =
        runTestWithServer(Application::attachTestServices) { _, _ ->
            val manager = AccountManagerImpl(KeyValueStore(InMemoryKeyValueStoreDelegate()))
            val keySource = AccountKeySource(manager)
            val lockers = LockersClient.create(
                rpcClient = OfflineRpcClient(),
                storeDelegate = InMemoryStoreDelegate(),
                keyValueStore = KeyValueStore(InMemoryKeyValueStoreDelegate()),
                keySource = keySource,
                appVersion = Version(0, 0, 1),
                lockKeySource = keySource,
            )
            manager.start(lockers)
            manager.createAccount()

            // Ready arrives without a connection, carrying the locally derived ids.
            val ready = manager.lifecycle.first { it is Lifecycle.Ready } as Lifecycle.Ready
            assertTrue(ready.accountId.isNotEmpty())
            assertTrue(ready.privateRoom.rawValue.isNotEmpty())
            assertFalse(lockers.isConnected.first())

            manager.stop()
            lockers.close()
        }

    @Test(timeout = 30_000)
    fun `create account locks the private room, mirrors the session id, and rejects strangers`() =
        runTestWithServer(Application::attachTestServices) { server, _ ->
            // manager → keySource(manager) → client(keySource) → manager.start(client). No cycle.
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

            // No key yet → NoAccount.
            assertTrue(manager.lifecycle.first() is Lifecycle.NoAccount)

            // CreateAccount unblocks the session and drives → Ready.
            manager.createAccount()
            val ready = manager.lifecycle.first { it is Lifecycle.Ready } as Lifecycle.Ready
            val roomId = ready.privateRoom

            // AccountState is present, and its mirrored session id matches the client's. Ready is
            // offline-first, so poll until the connected-tick room init has landed.
            val stateClient = lockers.typed(
                AccountKeyspaces.ACCOUNT_STATE, AccountState::toByteArray, AccountState.Companion::fromByteArray,
            )
            var state = stateClient.getLocker(roomId, AccountKeyspaces.ACCOUNT_STATE_LOCKER)
            while (state == null || state.sessionId.isEmpty()) {
                delay(50)
                state = stateClient.getLocker(roomId, AccountKeyspaces.ACCOUNT_STATE_LOCKER)
            }
            val sessionId = lockers.sessionId.value
            assertNotNull(sessionId)
            assertTrue(state.sessionId.contentEquals(sessionId.rawValue))

            // A different identity cannot write to this public-key-locked room.
            val stranger = AccountManagerImpl(KeyValueStore(InMemoryKeyValueStoreDelegate()))
            val strangerKeySource = AccountKeySource(stranger)
            val strangerLockers = LockersClient.create(
                rpcClient = server.rpcClient,
                storeDelegate = InMemoryStoreDelegate(),
                keyValueStore = KeyValueStore(InMemoryKeyValueStoreDelegate()),
                keySource = strangerKeySource,
                appVersion = Version(0, 0, 1),
                lockKeySource = strangerKeySource,
            )
            stranger.createAccount()
            strangerLockers.awaitConnected()
            val strangerClient = strangerLockers.typed(
                AccountKeyspaces.ACCOUNT_STATE, AccountState::toByteArray, AccountState.Companion::fromByteArray,
            )
            strangerClient.subscribeToRoom(roomId)
            assertFailsWith<LockerWriteException> {
                strangerClient.updateLocker(roomId, AccountKeyspaces.ACCOUNT_STATE_LOCKER) { it.copy { schemaVersion = 99 } }
            }

            // Sign out drives → SignedOut and revokes the identity.
            manager.signOut()
            manager.lifecycle.first { it is Lifecycle.SignedOut }
            assertFalse(manager.hasSessionKey())

            manager.stop()
            strangerLockers.close()
            lockers.close()
        }
}
