package com.latenighthack.social.rooms.domain

import com.latenighthack.ktbuf.net.RpcClient
import com.latenighthack.ktbuf.test.server.runTestWithServer
import com.latenighthack.ktcrypto.SHA256
import com.latenighthack.ktcrypto.digest
import com.latenighthack.ktstore.InMemoryKeyValueStoreDelegate
import com.latenighthack.ktstore.InMemoryStoreDelegate
import com.latenighthack.ktstore.KeyValueStore
import com.latenighthack.lockers.common.RoomKeying
import com.latenighthack.lockers.common.v1.LockerId
import com.latenighthack.lockers.common.v1.LockerKeyspace
import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.lockers.common.v1.Version
import com.latenighthack.lockers.connector.LockerWriteException
import com.latenighthack.lockers.connector.LockersClient
import com.latenighthack.lockers.server.attachTestServices
import com.latenighthack.lockers.server.rpcClient
import com.latenighthack.social.account.domain.AccountKeySource
import com.latenighthack.social.account.domain.AccountManager
import com.latenighthack.social.account.domain.AccountManagerImpl
import com.latenighthack.social.profiles.domain.MyProfilesManagerImpl
import com.latenighthack.social.profiles.domain.ProfileKeySource
import com.latenighthack.social.profiles.v1.ProfileId
import com.latenighthack.social.rooms.v1.Member
import com.latenighthack.social.rooms.v1.SealedEnvelope
import com.latenighthack.social.rooms.v1.fromByteArray
import com.latenighthack.social.rooms.v1.toByteArray
import io.ktor.server.application.Application
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RoomsManagerIntegrationTest {

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
        accountStore: KeyValueStore = KeyValueStore(InMemoryKeyValueStoreDelegate()),
    ): Party {
        val account = AccountManagerImpl(accountStore)
        val accountKeySource = AccountKeySource(account)
        val myProfiles = MyProfilesManagerImpl(account)
        val profileKeySource = ProfileKeySource(myProfiles, accountKeySource)
        val rooms = RoomsManagerImpl(account, myProfiles)
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
    fun `group invite shares the key, both become members, non-member can read but not write`() =
        runTestWithServer(Application::attachTestServices) { server, _ ->
            val alice = newParty(server.rpcClient)
            val bob = newParty(server.rpcClient)
            val aliceProfile = alice.myProfiles.createProfile("Alice")
            val bobProfile = bob.myProfiles.createProfile("Bob")

            val roomId = alice.rooms.createGroup("Team")
            alice.rooms.invite(roomId, listOf(bobProfile))

            // Bob unseals the invite from his inbox, adopts the group key, and joins.
            assertTrue(bob.rooms.watchRooms().first { it.contains(roomId) }.isNotEmpty())

            // Both sides converge on a two-member roster.
            val members = alice.rooms.watchMembers(roomId).first { it.size == 2 }
            assertTrue(members.contains(aliceProfile))
            assertTrue(members.contains(bobProfile))

            // Bob holds the shared key and can mutate the shared info; Alice sees it.
            assertEquals("Team", bob.rooms.watchInfo(roomId).first { it?.name() == "Team" }?.name())
            bob.rooms.updateInfo(roomId) { replaceDisclosure { name { value = "Squad" } } }
            assertEquals("Squad", alice.rooms.watchInfo(roomId).first { it?.name() == "Squad" }?.name())

            // A non-member can READ the room (server does not gate reads) ...
            val carol = newParty(server.rpcClient)
            val carolMembers = carol.lockers.typed(
                RoomsKeyspaces.MEMBERSHIP, Member::toByteArray, Member.Companion::fromByteArray,
            )
            carolMembers.subscribeToRoom(roomId)
            assertEquals(2, carolMembers.getAllLockers(roomId).size)

            // ... but cannot WRITE, because the room is locked to the group key she does not have.
            assertFailsWith<LockerWriteException> {
                carolMembers.updateLocker(roomId, LockerId(ByteArray(33), RoomsKeyspaces.MEMBERSHIP)) {
                    Member(joinedAtMillis = 1L)
                }
            }

            // Leaving removes Bob's own roster entry.
            bob.rooms.leave(roomId)
            assertEquals(listOf(aliceProfile), alice.rooms.watchMembers(roomId).first { it.size == 1 })

            carol.close()
            bob.close()
            alice.close()
        }

    @Test(timeout = 60_000)
    fun `a restored account recovers its rooms and shared keys from the account room`() =
        runTestWithServer(Application::attachTestServices) { server, _ ->
            // Device 1 creates an account, a profile, and a group room.
            val accountStore = KeyValueStore(InMemoryKeyValueStoreDelegate())
            val device1 = newParty(server.rpcClient, accountStore)
            device1.myProfiles.createProfile("Alice")
            val roomId = device1.rooms.createGroup("Team")
            // Let the record + info sync to the account room before the device goes away.
            device1.rooms.watchRooms().first { it.contains(roomId) }
            device1.rooms.watchInfo(roomId).first { it?.name() == "Team" }
            device1.close()

            // A fresh device restoring the same account identity (shared account store, but empty
            // room + lockers caches) recovers the room list and its shared key from the account room.
            val device2 = newParty(server.rpcClient, accountStore)
            assertTrue(device2.rooms.watchRooms().first { it.contains(roomId) }.isNotEmpty())
            assertEquals("Team", device2.rooms.watchInfo(roomId).first { it?.name() == "Team" }?.name())

            // The shared key was restored too, so the restored device can still write to the room.
            device2.rooms.updateInfo(roomId) { replaceDisclosure { name { value = "Renamed" } } }
            assertEquals("Renamed", device2.rooms.watchInfo(roomId).first { it?.name() == "Renamed" }?.name())

            device2.close()
        }

    @Test(timeout = 60_000)
    fun `watchRooms is ordered newest-first and markUpdated moves a room to the front`() =
        runTestWithServer(Application::attachTestServices) { server, _ ->
            val party = newParty(server.rpcClient)
            party.myProfiles.createProfile("Alice")
            val a = party.rooms.createGroup("A")
            val b = party.rooms.createGroup("B")

            // Both rooms are present in the list.
            party.rooms.watchRooms().first { it.toSet() == setOf(a, b) }

            // Touching A bumps its updated_at, moving it to the front.
            party.rooms.markUpdated(a)
            assertEquals(a, party.rooms.watchRooms().first { it.firstOrNull() == a }.first())

            // Touching B moves it back to the front.
            party.rooms.markUpdated(b)
            assertEquals(b, party.rooms.watchRooms().first { it.firstOrNull() == b }.first())

            party.close()
        }

    @Test(timeout = 60_000)
    fun `rendezvous rooms converge on the same id and both profiles can write`() =
        runTestWithServer(Application::attachTestServices) { server, _ ->
            val alice = newParty(server.rpcClient)
            val bob = newParty(server.rpcClient)
            alice.myProfiles.createProfile("Alice")
            val bobProfile = bob.myProfiles.createProfile("Bob")

            val roomId = alice.rooms.openRendezvous(bobProfile)

            // Bob receives the bootstrap invite and independently derives the SAME room id.
            val bobRooms = bob.rooms.watchRooms().first { it.isNotEmpty() }
            assertEquals(roomId, bobRooms.single())

            // Both are members of the 1:1 room.
            assertEquals(2, alice.rooms.watchMembers(roomId).first { it.size == 2 }.size)

            // Both hold the derived lock key, so info is mutable from either side.
            alice.rooms.updateInfo(roomId) { replaceDisclosure { name { value = "hi bob" } } }
            assertEquals("hi bob", bob.rooms.watchInfo(roomId).first { it?.name() == "hi bob" }?.name())
            bob.rooms.updateInfo(roomId) { replaceDisclosure { name { value = "hi alice" } } }
            assertEquals("hi alice", alice.rooms.watchInfo(roomId).first { it?.name() == "hi alice" }?.name())

            bob.close()
            alice.close()
        }

    @Test(timeout = 60_000)
    fun `profile inbox is open for sealed writes but content keyspace stays locked`() =
        runTestWithServer(Application::attachTestServices) { server, _ ->
            val bob = newParty(server.rpcClient)
            val bobProfile = bob.myProfiles.createProfile("Bob")
            // Ensure Bob's profile room is created and its content keyspace locked.
            bob.myProfiles.watchProfile(bobProfile).first { it != null }

            val outsider = newParty(server.rpcClient)
            val bobProfileRoom = RoomKeying.publicKeyed(bobProfile.rawValue)

            // An outsider can drop a sealed envelope into Bob's open inbox keyspace (4).
            val envelope = RoomSealing.seal(bobProfile.rawValue, byteArrayOf(1, 2, 3))
            val inbox = outsider.lockers.typed(
                RoomsKeyspaces.INBOX, SealedEnvelope::toByteArray, SealedEnvelope.Companion::fromByteArray,
            )
            inbox.subscribeToRoom(bobProfileRoom)
            val lockerId = LockerId(SHA256.digest(envelope.ephemeralPublicKey), RoomsKeyspaces.INBOX)
            inbox.updateLocker(bobProfileRoom, lockerId) { envelope }
            assertEquals(envelope, inbox.getLocker(bobProfileRoom, lockerId))

            // But the profile-content keyspace (3) is locked to Bob's profile key: an outsider write fails.
            val contentKeyspace = LockerKeyspace { value = 3L }
            val content = outsider.lockers.typed(
                contentKeyspace, SealedEnvelope::toByteArray, SealedEnvelope.Companion::fromByteArray,
            )
            content.subscribeToRoom(bobProfileRoom)
            assertFailsWith<LockerWriteException> {
                content.updateLocker(bobProfileRoom, LockerId(ByteArray(33), contentKeyspace)) { envelope }
            }

            outsider.close()
            bob.close()
        }
}
