package com.latenighthack.social.debug.domain

import com.latenighthack.ktbuf.net.RpcClient
import com.latenighthack.ktbuf.test.server.runTestWithServer
import com.latenighthack.ktcrypto.Secp256r1KeyPair
import com.latenighthack.ktcrypto.generate
import com.latenighthack.ktstore.InMemoryKeyValueStoreDelegate
import com.latenighthack.ktstore.InMemoryStoreDelegate
import com.latenighthack.ktstore.KeyValueStore
import com.latenighthack.lockers.common.v1.LockerId
import com.latenighthack.lockers.common.v1.LockerKeyspace
import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.lockers.common.v1.Version
import com.latenighthack.lockers.connector.AuthenticationKeySource
import com.latenighthack.lockers.connector.LockersClient
import com.latenighthack.lockers.server.attachTestServices
import com.latenighthack.lockers.server.rpcClient
import com.latenighthack.social.account.v1.AccountState
import com.latenighthack.social.account.v1.fromByteArray
import com.latenighthack.social.account.v1.toByteArray
import io.ktor.server.application.Application
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@Suppress("UNCHECKED_CAST")
class DebugManagerIntegrationTest {

    private val ACCOUNT_KEYSPACE = LockerKeyspace { value = 1L }
    private val UNKNOWN_KEYSPACE = LockerKeyspace { value = 30L }

    private suspend fun newClient(rpcClient: RpcClient): LockersClient {
        val sessionKeyPair = Secp256r1KeyPair.generate()
        val keySource = object : AuthenticationKeySource {
            override suspend fun getSessionKeyPair() = sessionKeyPair
            override suspend fun hasSessionKeyPair() = true
            override suspend fun generateSessionKeyPair() {}
            override suspend fun revokeKeys() {}
        }
        val lockers = LockersClient.create(
            rpcClient = rpcClient,
            storeDelegate = InMemoryStoreDelegate(),
            keyValueStore = KeyValueStore(InMemoryKeyValueStoreDelegate()),
            keySource = keySource,
            appVersion = Version(0, 0, 1),
        )
        lockers.awaitConnected()
        return lockers
    }

    private fun codecs() = LockerCodecs.builder()
        .register(1L, "account") { AccountState.fromByteArray(it).toValue() }
        .build()

    @Test(timeout = 60_000)
    fun `groups known lockers by keyspace, decoding a registered keyspace and raw-falling-back an unregistered one`() =
        runTestWithServer(Application::attachTestServices) { server, _ ->
            val lockers = newClient(server.rpcClient)
            val roomId = RoomId(Random.nextBytes(32))
            val account = lockers.typed(ACCOUNT_KEYSPACE, AccountState::toByteArray, AccountState.Companion::fromByteArray)
            val unknown = lockers.typed(UNKNOWN_KEYSPACE, AccountState::toByteArray, AccountState.Companion::fromByteArray)
            account.subscribeToRoom(roomId)

            account.updateLocker(roomId, LockerId(Random.nextBytes(16), ACCOUNT_KEYSPACE)) {
                AccountState(createdAtMillis = 42L, schemaVersion = 7)
            }
            unknown.updateLocker(roomId, LockerId(Random.nextBytes(16), UNKNOWN_KEYSPACE)) {
                AccountState(createdAtMillis = 99L)
            }

            val debug = DebugManagerImpl(codecs())
            debug.start(lockers)

            // Both lockers are already cached, so they surface in the seed the flow emits first.
            val dump = debug.watchLockers().first { it.containsKey("1 (account)") && it.containsKey("30") }

            val accountEntries = dump["1 (account)"] as List<Map<String, Any?>>
            assertEquals(1, accountEntries.size)
            val accountValue = accountEntries.first()["value"] as Map<String, Any?>
            assertEquals(42L, accountValue["createdAtMillis"])
            assertEquals(7, accountValue["schemaVersion"])
            assertNotNull(accountEntries.first()["room"])
            assertNotNull(accountEntries.first()["key"])

            val unknownEntries = dump["30"] as List<Map<String, Any?>>
            assertEquals(1, unknownEntries.size)
            val unknownValue = unknownEntries.first()["value"] as Map<String, Any?>
            // No codec for keyspace 30: the value is the raw base64 of the stored bytes, not decoded.
            assertNotNull(unknownValue["raw"])
            assertEquals(null, unknownValue["createdAtMillis"])

            debug.stop()
            lockers.close()
        }

    @Test(timeout = 60_000)
    fun `re-emits as new lockers are written`() =
        runTestWithServer(Application::attachTestServices) { server, _ ->
            val lockers = newClient(server.rpcClient)
            val roomId = RoomId(Random.nextBytes(32))
            val account = lockers.typed(ACCOUNT_KEYSPACE, AccountState::toByteArray, AccountState.Companion::fromByteArray)
            account.subscribeToRoom(roomId)

            val debug = DebugManagerImpl(codecs())
            debug.start(lockers)

            val ready = CompletableDeferred<Unit>()
            val twoSeen = CompletableDeferred<Map<String, Any?>>()
            val job = launch {
                debug.watchLockers().collect { dump ->
                    // The first emission is the (empty) seed; once we've seen it the fold is live on
                    // the change stream, so writes after this point arrive as fresh emissions.
                    if (!ready.isCompleted) {
                        ready.complete(Unit)
                        return@collect
                    }
                    val entries = dump["1 (account)"] as? List<Map<String, Any?>>
                    if (entries != null && entries.size >= 2 && !twoSeen.isCompleted) {
                        twoSeen.complete(dump)
                    }
                }
            }

            ready.await()
            account.updateLocker(roomId, LockerId(Random.nextBytes(16), ACCOUNT_KEYSPACE)) {
                AccountState(createdAtMillis = 1L)
            }
            account.updateLocker(roomId, LockerId(Random.nextBytes(16), ACCOUNT_KEYSPACE)) {
                AccountState(createdAtMillis = 2L)
            }

            val dump = twoSeen.await()
            val entries = dump["1 (account)"] as List<Map<String, Any?>>
            assertEquals(2, entries.size)

            job.cancel()
            debug.stop()
            lockers.close()
        }
}
