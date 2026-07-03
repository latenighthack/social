package com.latenighthack.social.messages.domain

import com.latenighthack.ktbuf.net.RpcClient
import com.latenighthack.ktbuf.test.server.runTestWithServer
import com.latenighthack.ktcrypto.Secp256r1KeyPair
import com.latenighthack.ktcrypto.generate
import com.latenighthack.ktstore.InMemoryKeyValueStoreDelegate
import com.latenighthack.ktstore.InMemoryStoreDelegate
import com.latenighthack.ktstore.KeyValueStore
import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.lockers.common.v1.Version
import com.latenighthack.lockers.connector.AuthenticationKeySource
import com.latenighthack.lockers.connector.LockersClient
import com.latenighthack.lockers.server.attachTestServices
import com.latenighthack.lockers.server.rpcClient
import com.latenighthack.social.messages.v1.Draft
import io.ktor.server.application.Application
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DraftsManagerIntegrationTest {

    // Drafts never touch lockers, so a session key is all the client needs to be constructed.
    private class FakeKeySource : AuthenticationKeySource {
        private var keyPair: Secp256r1KeyPair? = null
        override suspend fun getSessionKeyPair(): Secp256r1KeyPair =
            keyPair ?: Secp256r1KeyPair.generate().also { keyPair = it }
        override suspend fun hasSessionKeyPair(): Boolean = keyPair != null
        override suspend fun generateSessionKeyPair() {
            keyPair = Secp256r1KeyPair.generate()
        }
        override suspend fun revokeKeys() {
            keyPair = null
        }
    }

    private suspend fun lockersClient(rpcClient: RpcClient): LockersClient =
        LockersClient.create(
            rpcClient = rpcClient,
            storeDelegate = InMemoryStoreDelegate(),
            keyValueStore = KeyValueStore(InMemoryKeyValueStoreDelegate()),
            keySource = FakeKeySource(),
            appVersion = Version(0, 0, 1),
        )

    private fun room(seed: Byte) = RoomId(rawValue = ByteArray(32) { seed })

    @Test(timeout = 60_000)
    fun `a saved draft is observable and replaced by a later save`() =
        runTestWithServer(Application::attachTestServices) { server, _ ->
            val lockers = lockersClient(server.rpcClient)
            val drafts = DraftsManagerImpl(InMemoryStoreDelegate())
            drafts.start(lockers)
            val roomId = room(1)

            drafts.save(roomId, Draft { text = "first" })
            assertEquals("first", drafts.watchDraft(roomId).first { it?.text == "first" }?.text)

            drafts.save(roomId, Draft { text = "second" })
            assertEquals("second", drafts.watchDraft(roomId).first { it?.text == "second" }?.text)

            drafts.stop()
            lockers.close()
        }

    @Test(timeout = 60_000)
    fun `clearing a room's draft leaves other rooms untouched`() =
        runTestWithServer(Application::attachTestServices) { server, _ ->
            val lockers = lockersClient(server.rpcClient)
            val drafts = DraftsManagerImpl(InMemoryStoreDelegate())
            drafts.start(lockers)
            val roomA = room(1)
            val roomB = room(2)

            drafts.save(roomA, Draft { text = "for A" })
            drafts.save(roomB, Draft { text = "for B" })
            drafts.watchDraft(roomB).first { it?.text == "for B" }

            drafts.clear(roomA)
            assertNull(drafts.watchDraft(roomA).first())
            assertEquals("for B", drafts.watchDraft(roomB).first()?.text)

            drafts.stop()
            lockers.close()
        }

    @Test(timeout = 60_000)
    fun `a saved draft is written through to the persistent draft store`() =
        runTestWithServer(Application::attachTestServices) { server, _ ->
            val lockers = lockersClient(server.rpcClient)
            val delegate = InMemoryStoreDelegate()
            val drafts = DraftsManagerImpl(delegate)
            drafts.start(lockers)
            val roomId = room(3)

            drafts.save(roomId, Draft { text = "durable" })

            // Read straight from the ktstore backing (the table the manager already created) to prove the
            // write reached durable storage, not just the in-memory flow.
            val stored = DraftStore(delegate).getAllDrafts()
            assertEquals(1, stored.size)
            assertEquals("durable", stored.single().draft?.text)
            assertContentEquals(roomId.rawValue, stored.single().roomId)

            drafts.stop()
            lockers.close()
        }
}
