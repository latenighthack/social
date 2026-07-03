package com.latenighthack.social.messages.domain

import com.latenighthack.ktbuf.net.RpcClient
import com.latenighthack.ktbuf.test.server.runTestWithServer
import com.latenighthack.ktcrypto.Secp256r1KeyPair
import com.latenighthack.ktcrypto.generate
import com.latenighthack.ktstore.InMemoryKeyValueStoreDelegate
import com.latenighthack.ktstore.InMemoryStoreDelegate
import com.latenighthack.ktstore.KeyValueStore
import com.latenighthack.lockers.common.v1.LockerId
import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.lockers.common.v1.Version
import com.latenighthack.lockers.connector.LockerWriteException
import com.latenighthack.lockers.connector.LockersClient
import com.latenighthack.lockers.server.attachTestServices
import com.latenighthack.lockers.server.rpcClient
import com.latenighthack.social.account.domain.AccountKeySource
import com.latenighthack.social.account.domain.AccountManager
import com.latenighthack.social.account.domain.AccountManagerImpl
import com.latenighthack.social.common.domain.sign
import com.latenighthack.social.common.v1.SignedContent
import com.latenighthack.social.common.v1.fromByteArray
import com.latenighthack.social.common.v1.toByteArray
import com.latenighthack.social.messages.v1.Draft
import com.latenighthack.social.messages.v1.MessagePayload
import com.latenighthack.social.messages.v1.toByteArray
import com.latenighthack.social.profiles.domain.MyProfilesManagerImpl
import com.latenighthack.social.profiles.domain.ProfileKeySource
import com.latenighthack.social.rooms.domain.RoomsKeySource
import com.latenighthack.social.rooms.domain.RoomsManagerImpl
import io.ktor.server.application.Application
import kotlinx.coroutines.flow.first
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MessagesManagerIntegrationTest {

    private class Party(
        val myProfiles: MyProfilesManagerImpl,
        val rooms: RoomsManagerImpl,
        val messages: MessagesManagerImpl,
        val drafts: DraftsManagerImpl,
        val lockers: LockersClient,
    ) {
        fun close() {
            drafts.stop()
            messages.stop()
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
        val messages = MessagesManagerImpl(rooms, myProfiles, InMemoryStoreDelegate())
        val drafts = DraftsManagerImpl(InMemoryStoreDelegate())
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
        messages.start(lockers)
        drafts.start(lockers)
        account.createAccount()
        account.lifecycle.first { it is AccountManager.Lifecycle.Ready }
        return Party(myProfiles, rooms, messages, drafts, lockers)
    }

    @Test(timeout = 60_000)
    fun `a group message is delivered with signed sender attribution`() =
        runTestWithServer(Application::attachTestServices) { server, _ ->
            val alice = newParty(server.rpcClient)
            val bob = newParty(server.rpcClient)
            val aliceProfile = alice.myProfiles.createProfile("Alice")
            val bobProfile = bob.myProfiles.createProfile("Bob")

            val roomId = alice.rooms.createGroup("Team")
            alice.rooms.invite(roomId, listOf(bobProfile))
            bob.rooms.watchRooms().first { it.contains(roomId) }

            alice.messages.send(roomId, Draft { text = "hello bob" })

            val delivered = bob.messages.watchMessages(roomId).first { list -> list.any { it.text == "hello bob" } }
            val message = delivered.first { it.text == "hello bob" }
            assertTrue(message.senderProfileId.contentEquals(aliceProfile.rawValue))

            bob.close()
            alice.close()
        }

    @Test(timeout = 60_000)
    fun `rendezvous peers exchange messages`() =
        runTestWithServer(Application::attachTestServices) { server, _ ->
            val alice = newParty(server.rpcClient)
            val bob = newParty(server.rpcClient)
            alice.myProfiles.createProfile("Alice")
            val bobProfile = bob.myProfiles.createProfile("Bob")

            val roomId = alice.rooms.openRendezvous(bobProfile)
            bob.rooms.watchRooms().first { it.contains(roomId) }

            alice.messages.send(roomId, Draft { text = "hi bob" })
            bob.messages.watchMessages(roomId).first { list -> list.any { it.text == "hi bob" } }

            bob.messages.send(roomId, Draft { text = "hi alice" })
            alice.messages.watchMessages(roomId).first { list -> list.any { it.text == "hi alice" } }

            bob.close()
            alice.close()
        }

    @Test(timeout = 60_000)
    fun `receiving a message bumps the room to the front of the list`() =
        runTestWithServer(Application::attachTestServices) { server, _ ->
            val alice = newParty(server.rpcClient)
            val bob = newParty(server.rpcClient)
            alice.myProfiles.createProfile("Alice")
            val bobProfile = bob.myProfiles.createProfile("Bob")

            val roomA = alice.rooms.createGroup("A")
            val roomB = alice.rooms.createGroup("B")
            alice.rooms.invite(roomA, listOf(bobProfile))
            alice.rooms.invite(roomB, listOf(bobProfile))
            bob.rooms.watchRooms().first { it.containsAll(listOf(roomA, roomB)) }

            // A message into room A moves it to the front of Bob's room list.
            alice.messages.send(roomA, Draft { text = "ping" })
            assertTrue(bob.rooms.watchRooms().first { it.firstOrNull() == roomA }.isNotEmpty())

            bob.close()
            alice.close()
        }

    @Test(timeout = 60_000)
    fun `a forged-authorship message is dropped and non-members cannot post`() =
        runTestWithServer(Application::attachTestServices) { server, _ ->
            val alice = newParty(server.rpcClient)
            val bob = newParty(server.rpcClient)
            alice.myProfiles.createProfile("Alice")
            val bobProfile = bob.myProfiles.createProfile("Bob")

            val roomId = alice.rooms.createGroup("Team")
            alice.rooms.invite(roomId, listOf(bobProfile))
            bob.rooms.watchRooms().first { it.contains(roomId) }

            // Alice (a member, so the room lock lets her write) posts a message that CLAIMS Bob as the
            // sender but is signed by an unrelated key — the signature won't match bobProfile.
            val forger = Secp256r1KeyPair.generate()
            val forgedPayload = MessagePayload(
                roomId = roomId.rawValue,
                senderProfileId = bobProfile.rawValue,
                sentAtMillis = 1L,
                text = "forged",
            )
            val forged = sign(forger, MessageSigning.LABEL, forgedPayload.toByteArray())
            alice.lockers.typed(MessagesKeyspaces.MESSAGES, SignedContent::toByteArray, SignedContent.Companion::fromByteArray)
                .updateLocker(roomId, LockerId(Random.nextBytes(32), MessagesKeyspaces.MESSAGES)) { forged }

            // A genuine message posted after it; once it arrives the forged one has been processed.
            alice.messages.send(roomId, Draft { text = "genuine" })
            val delivered = bob.messages.watchMessages(roomId).first { list -> list.any { it.text == "genuine" } }
            assertTrue(delivered.none { it.text == "forged" })

            // A non-member cannot post at all: the messages keyspace is under the room lock.
            val carol = newParty(server.rpcClient)
            val carolMessages = carol.lockers.typed(
                MessagesKeyspaces.MESSAGES, SignedContent::toByteArray, SignedContent.Companion::fromByteArray,
            )
            carolMessages.subscribeToRoom(roomId)
            assertFailsWith<LockerWriteException> {
                carolMessages.updateLocker(roomId, LockerId(Random.nextBytes(32), MessagesKeyspaces.MESSAGES)) { forged }
            }

            carol.close()
            bob.close()
            alice.close()
        }

    @Test(timeout = 60_000)
    fun `a restored account re-syncs its message history`() =
        runTestWithServer(Application::attachTestServices) { server, _ ->
            // Device 1 creates an account, a profile, a group, and sends a message.
            val accountStore = KeyValueStore(InMemoryKeyValueStoreDelegate())
            val device1 = newParty(server.rpcClient, accountStore)
            device1.myProfiles.createProfile("Alice")
            val roomId = device1.rooms.createGroup("Team")
            device1.rooms.watchRooms().first { it.contains(roomId) }
            device1.messages.send(roomId, Draft { text = "note to self" })
            device1.messages.watchMessages(roomId).first { list -> list.any { it.text == "note to self" } }
            device1.close()

            // A fresh device restoring the same account recovers the room and re-syncs the message.
            val device2 = newParty(server.rpcClient, accountStore)
            device2.messages.watchMessages(roomId).first { list -> list.any { it.text == "note to self" } }

            device2.close()
        }
}
