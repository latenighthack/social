package com.latenighthack.social.readreceipts.domain

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
import com.latenighthack.social.messages.domain.MessagesManagerImpl
import com.latenighthack.social.messages.v1.Draft
import com.latenighthack.social.profiles.domain.MyProfilesManagerImpl
import com.latenighthack.social.profiles.domain.ProfileKeySource
import com.latenighthack.social.rooms.domain.RoomsKeySource
import com.latenighthack.social.rooms.domain.RoomsManagerImpl
import io.ktor.server.application.Application
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertTrue

class ReadReceiptsManagerIntegrationTest {

    private class Party(
        val myProfiles: MyProfilesManagerImpl,
        val rooms: RoomsManagerImpl,
        val messages: MessagesManagerImpl,
        val readReceipts: ReadReceiptsManagerImpl,
        val lockers: LockersClient,
    ) {
        fun close() {
            readReceipts.stop()
            messages.stop()
            rooms.stop()
            myProfiles.stop()
            lockers.close()
        }
    }

    private suspend fun newParty(rpcClient: RpcClient): Party {
        val account = AccountManagerImpl(KeyValueStore(InMemoryKeyValueStoreDelegate()))
        val accountKeySource = AccountKeySource(account)
        val myProfiles = MyProfilesManagerImpl(account)
        val profileKeySource = ProfileKeySource(myProfiles, accountKeySource)
        val rooms = RoomsManagerImpl(account, myProfiles)
        val roomsKeySource = RoomsKeySource(rooms, profileKeySource)
        val messages = MessagesManagerImpl(rooms, myProfiles, InMemoryStoreDelegate())
        val readReceipts = ReadReceiptsManagerImpl(rooms, messages)
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
        readReceipts.start(lockers)
        account.createAccount()
        account.lifecycle.first { it is AccountManager.Lifecycle.Ready }
        return Party(myProfiles, rooms, messages, readReceipts, lockers)
    }

    @Test(timeout = 60_000)
    fun `markRead publishes the reader's pointer at the latest message to other members`() =
        runTestWithServer(Application::attachTestServices) { server, _ ->
            val alice = newParty(server.rpcClient)
            val bob = newParty(server.rpcClient)
            alice.myProfiles.createProfile("Alice")
            val bobProfile = bob.myProfiles.createProfile("Bob")

            val roomId = alice.rooms.createGroup("Team")
            alice.rooms.invite(roomId, listOf(bobProfile))
            bob.rooms.watchRooms().first { it.contains(roomId) }

            alice.messages.send(roomId, Draft { text = "hello" })
            // Bob sees the message, then marks it read; his pointer must land on that message's id.
            bob.messages.watchMessages(roomId).first { it.isNotEmpty() }
            val latestId = bob.messages.watchMessageIds(roomId).first { it.isNotEmpty() }.last()
            bob.readReceipts.markRead(roomId)

            val receipts = alice.readReceipts.watchReadReceipts(roomId).first { bobProfile in it }
            assertTrue(receipts[bobProfile]?.rawValue?.contentEquals(latestId.rawValue) == true)

            bob.close()
            alice.close()
        }

    @Test(timeout = 60_000)
    fun `the read pointer advances as new messages are sent and re-marked`() =
        runTestWithServer(Application::attachTestServices) { server, _ ->
            val alice = newParty(server.rpcClient)
            val bob = newParty(server.rpcClient)
            alice.myProfiles.createProfile("Alice")
            val bobProfile = bob.myProfiles.createProfile("Bob")

            val roomId = alice.rooms.createGroup("Team")
            alice.rooms.invite(roomId, listOf(bobProfile))
            bob.rooms.watchRooms().first { it.contains(roomId) }

            alice.messages.send(roomId, Draft { text = "one" })
            bob.messages.watchMessages(roomId).first { it.size == 1 }
            bob.readReceipts.markRead(roomId)
            val firstId = bob.messages.watchMessageIds(roomId).first { it.size == 1 }.last()
            alice.readReceipts.watchReadReceipts(roomId)
                .first { it[bobProfile]?.rawValue?.contentEquals(firstId.rawValue) == true }

            alice.messages.send(roomId, Draft { text = "two" })
            bob.messages.watchMessages(roomId).first { it.size == 2 }
            bob.readReceipts.markRead(roomId)
            val secondId = bob.messages.watchMessageIds(roomId).first { it.size == 2 }.last()
            alice.readReceipts.watchReadReceipts(roomId)
                .first { it[bobProfile]?.rawValue?.contentEquals(secondId.rawValue) == true }

            bob.close()
            alice.close()
        }

    @Test(timeout = 60_000)
    fun `markRead is a no-op in a room with no messages`() =
        runTestWithServer(Application::attachTestServices) { server, _ ->
            val alice = newParty(server.rpcClient)
            val bob = newParty(server.rpcClient)
            alice.myProfiles.createProfile("Alice")
            val bobProfile = bob.myProfiles.createProfile("Bob")

            val roomId = alice.rooms.createGroup("Team")
            alice.rooms.invite(roomId, listOf(bobProfile))
            bob.rooms.watchRooms().first { it.contains(roomId) }

            // No messages yet: markRead writes nothing, so no receipt appears for Bob.
            bob.readReceipts.markRead(roomId)
            val receipts = alice.readReceipts.watchReadReceipts(roomId).first()
            assertTrue(bobProfile !in receipts)

            bob.close()
            alice.close()
        }
}
