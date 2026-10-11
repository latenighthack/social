package com.latenighthack.social.account.domain

import com.latenighthack.ktstore.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlin.test.*

class IdentityTransitionRegressionTest {
    @Test fun cancelledRestoreMustNotAdoptAnUnpersistedIdentity(): Unit = runBlocking {
        val account = AccountManagerImpl(KeyValueStore(InMemoryKeyValueStoreDelegate()))
        val offline = object : com.latenighthack.ktbuf.net.RpcClient {
            override suspend fun unaryCall(method: com.latenighthack.ktbuf.net.RpcMethodSpecifier, headers: Map<String,String>, request: ByteArray): com.latenighthack.ktbuf.net.RpcResponse = awaitCancellation()
            override suspend fun serverStreamingCall(method: com.latenighthack.ktbuf.net.RpcMethodSpecifier, block: suspend com.latenighthack.ktbuf.net.RpcServerStream.() -> Unit, readyCallback: () -> Unit): Unit = awaitCancellation()
        }
        val client = com.latenighthack.lockers.connector.LockersClient.create(rpcClient=offline,
            database=Database(com.latenighthack.lockers.connector.ConnectorStorage.configuration("review-restore"), InMemoryStoreDelegate()),
            keyValueStore=KeyValueStore(InMemoryKeyValueStoreDelegate()), keySource=AccountKeySource(account),
            appVersion=com.latenighthack.lockers.common.v1.Version(0,0,1))
        account.start(client)
        try {
            delay(50)
            val timedOut = withTimeoutOrNull(100) { account.restoreAccount(ByteArray(32) { 1 }) }
            assertNull(timedOut)
            assertFalse(account.hasSessionKey(), "cancelled restore left its tentative identity installed")
        } finally { account.stop(); client.close() }
    }

    @Test fun concurrentCreateCallsMustReturnOneIdentity() = runTest {
        val base = InMemoryKeyValueStoreDelegate()
        val firstRead = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        var reads = 0
        val delegate = object : KeyValueStoreDelegate by base {
            override suspend fun getItem(key: String): String? {
                val value = base.getItem(key)
                if (++reads == 1) { firstRead.complete(Unit); releaseRead.await() }
                return value
            }
        }
        val account = AccountManagerImpl(KeyValueStore(delegate))
        val first = async { account.createAccount() }
        firstRead.await()
        val second = async { account.createAccount() }
        runCurrent()
        releaseRead.complete(Unit)
        val ids = listOf(first, second).awaitAll()
        assertContentEquals(ids[0], ids[1], "concurrent creation minted distinct account identities")
    }
}
