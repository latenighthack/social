package com.latenighthack.social.rooms.domain

import com.latenighthack.ktbuf.net.RpcClient
import com.latenighthack.ktbuf.test.server.runTestWithServer
import com.latenighthack.ktstore.InMemoryKeyValueStoreDelegate
import com.latenighthack.ktstore.InMemoryStoreDelegate
import com.latenighthack.ktstore.KeyValueStore
import com.latenighthack.lockers.common.v1.Version
import com.latenighthack.lockers.connector.LockersClient
import com.latenighthack.lockers.server.attachTestServices
import com.latenighthack.lockers.server.rpcClient
import com.latenighthack.social.account.domain.AccountKeySource
import com.latenighthack.social.account.domain.AccountManager
import com.latenighthack.social.account.domain.AccountManagerImpl
import com.latenighthack.social.profiles.domain.MyProfilesManagerImpl
import com.latenighthack.social.profiles.domain.ProfileKeySource
import io.ktor.server.application.Application
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DerivedRoomsIntegrationTest {

    private class Party(
        val myProfiles: MyProfilesManagerImpl,
        val rooms: RoomsManagerImpl,
        val lockers: LockersClient,
    ) {
        fun close() {
            rooms.stop()
            myProfiles.stop()
            lockers.close()
        }
    }

    private suspend fun newParty(
        rpcClient: RpcClient,
        joinClient: JoinClient = FakeJoinClient(),
    ): Party {
        val account = AccountManagerImpl(KeyValueStore(InMemoryKeyValueStoreDelegate()))
        val accountKeySource = AccountKeySource(account)
        val myProfiles = MyProfilesManagerImpl(account)
        val profileKeySource = ProfileKeySource(myProfiles, accountKeySource)
        val rooms = RoomsManagerImpl(account, myProfiles, joinClient)
        val roomsKeySource = RoomsKeySource(rooms, profileKeySource)
        val lockers = LockersClient.create(
            rpcClient = rpcClient,
            storeDelegate = InMemoryStoreDelegate(),
            keyValueStore = KeyValueStore(InMemoryKeyValueStoreDelegate()),
            keySource = accountKeySource,
            appVersion = Version(0, 0, 1),
            lockKeySource = roomsKeySource,
        )
        account.start(lockers)
        myProfiles.start(lockers)
        rooms.start(lockers)
        account.createAccount()
        account.lifecycle.first { it is AccountManager.Lifecycle.Ready }
        return Party(myProfiles, rooms, lockers)
    }

    @Test(timeout = 60_000)
    fun `every parent member derives and opens the same child room`() =
        runTestWithServer(Application::attachTestServices) { server, _ ->
            val join = FakeJoinClient()
            val alice = newParty(server.rpcClient, join)
            val bob = newParty(server.rpcClient, join)
            val aliceProfile = alice.myProfiles.createProfile("Alice")
            val bobProfile = bob.myProfiles.createProfile("Bob")

            val parent = alice.rooms.createGroup("Team")
            bob.rooms.joinByCode(alice.rooms.createInviteCode(parent))
            bob.rooms.watchRooms().first { it.contains(parent) }

            val salt = ByteArray(32) { 7 }
            val child = alice.rooms.openDerivedRoom(parent, "test.child.v1", salt)

            // A second member derives the same id without any grant, and opening is idempotent —
            // including the repeat root lock (both sides sign the identical derived key).
            assertEquals(child, bob.rooms.deriveChildRoomId(parent, "test.child.v1", salt))
            assertEquals(child, bob.rooms.openDerivedRoom(parent, "test.child.v1", salt))
            assertEquals(child, alice.rooms.openDerivedRoom(parent, "test.child.v1", salt))

            // Both are members of the child, and both hold its write key (shared info mutates).
            val members = alice.rooms.watchMembers(child).first { it.size == 2 }
            assertTrue(members.contains(aliceProfile))
            assertTrue(members.contains(bobProfile))
            bob.rooms.updateInfo(child) { replaceDisclosure { name { value = "Widget" } } }
            assertEquals("Widget", alice.rooms.watchInfo(child).first { it?.name() == "Widget" }?.name())

            bob.close()
            alice.close()
        }

    @Test(timeout = 60_000)
    fun `derivation is scoped by purpose and salt and gated on parent membership`() =
        runTestWithServer(Application::attachTestServices) { server, _ ->
            val alice = newParty(server.rpcClient)
            alice.myProfiles.createProfile("Alice")
            val parent = alice.rooms.createGroup("Team")

            val a = alice.rooms.deriveChildRoomId(parent, "p.v1", ByteArray(32) { 1 })
            val b = alice.rooms.deriveChildRoomId(parent, "p.v1", ByteArray(32) { 2 })
            val c = alice.rooms.deriveChildRoomId(parent, "q.v1", ByteArray(32) { 1 })
            assertFalse(a.rawValue.contentEquals(b.rawValue), "salt must separate children")
            assertFalse(a.rawValue.contentEquals(c.rawValue), "purpose must separate children")
            assertFalse(a.rawValue.contentEquals(parent.rawValue))

            // A stranger to the parent cannot derive at all (no parent key).
            val carol = newParty(server.rpcClient)
            carol.myProfiles.createProfile("Carol")
            assertFailsWith<IllegalStateException> {
                carol.rooms.deriveChildRoomId(parent, "p.v1", ByteArray(32) { 1 })
            }

            carol.close()
            alice.close()
        }
}
