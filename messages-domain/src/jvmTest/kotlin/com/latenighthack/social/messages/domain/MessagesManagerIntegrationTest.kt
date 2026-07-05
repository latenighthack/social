package com.latenighthack.social.messages.domain

import com.latenighthack.ktbuf.net.RpcClient
import com.latenighthack.ktbuf.test.server.runTestWithServer
import com.latenighthack.ktcrypto.Secp256r1KeyPair
import com.latenighthack.ktcrypto.encode
import com.latenighthack.ktcrypto.fromPrivateKey
import com.latenighthack.ktcrypto.generate
import com.latenighthack.lockers.common.RoomKeying
import com.latenighthack.ktstore.InMemoryKeyValueStoreDelegate
import com.latenighthack.ktstore.InMemoryStoreDelegate
import com.latenighthack.ktstore.KeyValueStore
import com.latenighthack.lockers.common.v1.LockerId
import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.lockers.common.v1.Version
import com.latenighthack.lockers.connector.LockKeySource
import com.latenighthack.lockers.connector.LockerWriteException
import com.latenighthack.lockers.connector.LockersClient
import com.latenighthack.lockers.server.attachTestServices
import com.latenighthack.lockers.server.rpcClient
import com.latenighthack.social.account.domain.AccountKeySource
import com.latenighthack.social.account.domain.AccountManager
import com.latenighthack.social.account.domain.AccountManagerImpl
import com.latenighthack.social.common.domain.Sealing
import com.latenighthack.social.common.domain.sign
import com.latenighthack.social.common.v1.SignedContent
import com.latenighthack.social.common.v1.fromByteArray
import com.latenighthack.social.common.v1.toByteArray
import com.latenighthack.social.messages.v1.Component
import com.latenighthack.social.messages.v1.Draft
import com.latenighthack.social.messages.v1.DraftAttachment
import com.latenighthack.social.messages.v1.MessageDeliveryStatus
import com.latenighthack.social.messages.v1.MessagePayload
import com.latenighthack.social.messages.v1.toByteArray
import com.latenighthack.social.profiles.domain.MyProfilesManagerImpl
import com.latenighthack.social.profiles.domain.ProfileKeySource
import com.latenighthack.social.rooms.domain.JoinClient
import com.latenighthack.social.rooms.domain.RoomsKeySource
import com.latenighthack.social.rooms.domain.RoomsManagerImpl
import com.latenighthack.social.rooms.v1.CreateInviteCodeRequest
import com.latenighthack.social.rooms.v1.CreateInviteCodeResponse
import com.latenighthack.social.rooms.v1.Invite
import com.latenighthack.social.rooms.v1.InviteCode
import com.latenighthack.social.rooms.v1.JoinRequest
import com.latenighthack.social.rooms.v1.JoinResponse
import com.latenighthack.social.rooms.v1.JoinResult
import com.latenighthack.social.rooms.v1.RevokeInviteCodeRequest
import com.latenighthack.social.rooms.v1.RevokeInviteCodeResponse
import com.latenighthack.social.rooms.v1.RoomKind
import com.latenighthack.social.rooms.v1.toByteArray
import io.ktor.server.application.Application
import kotlin.jvm.Volatile
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
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
        joinClient: JoinClient = sharedJoinClient,
        maxAttempts: Int = 8,
        backoffBaseMillis: Long = 1L,
        lockKeySourceFactory: (LockKeySource) -> LockKeySource = { it },
    ): Party {
        val account = AccountManagerImpl(accountStore)
        val accountKeySource = AccountKeySource(account)
        val myProfiles = MyProfilesManagerImpl(account)
        val profileKeySource = ProfileKeySource(myProfiles, accountKeySource)
        val rooms = RoomsManagerImpl(account, myProfiles, joinClient)
        val roomsKeySource = RoomsKeySource(rooms, profileKeySource)
        val messages = MessagesManagerImpl(
            rooms, myProfiles, InMemoryStoreDelegate(),
            maxAttempts = maxAttempts, backoffBaseMillis = backoffBaseMillis,
        )
        val drafts = DraftsManagerImpl(InMemoryStoreDelegate())
        val lockers = LockersClient.create(
            rpcClient = rpcClient,
            storeDelegate = InMemoryStoreDelegate(),
            keyValueStore = KeyValueStore(InMemoryKeyValueStoreDelegate()),
            keySource = accountKeySource,
            appVersion = Version(0, 0, 1),
            lockKeySource = lockKeySourceFactory(roomsKeySource),
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
            bob.rooms.joinByCode(alice.rooms.createInviteCode(roomId))
            bob.rooms.watchRooms().first { it.contains(roomId) }

            alice.messages.send(roomId, Draft { text = "hello bob" })

            val delivered = bob.messages.watchMessages(roomId).first { list -> list.any { it.payload.component?.text == "hello bob" } }
            val message = delivered.first { it.payload.component?.text == "hello bob" }
            assertTrue(message.payload.senderProfileId.contentEquals(aliceProfile.rawValue))

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

            // Both sessions must be subscribed to the room before either sends: a message rides as a
            // transient notification with no server-side backlog, so a not-yet-subscribed peer would
            // miss it. Awaiting membership sync forces each side's subscribeToRoom to complete.
            alice.rooms.watchMembers(roomId).first { it.size == 2 }
            bob.rooms.watchMembers(roomId).first { it.size == 2 }

            alice.messages.send(roomId, Draft { text = "hi bob" })
            bob.messages.watchMessages(roomId).first { list -> list.any { it.payload.component?.text == "hi bob" } }

            bob.messages.send(roomId, Draft { text = "hi alice" })
            alice.messages.watchMessages(roomId).first { list -> list.any { it.payload.component?.text == "hi alice" } }

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
            bob.rooms.joinByCode(alice.rooms.createInviteCode(roomA))
            bob.rooms.joinByCode(alice.rooms.createInviteCode(roomB))
            bob.rooms.watchRooms().first { it.containsAll(listOf(roomA, roomB)) }
            bob.rooms.watchMembers(roomA).first { it.size == 2 }

            // Bob never watches room A's messages, so it stays unloaded: the incoming message is
            // persisted and bumps the room to the front without pulling the room into memory.
            alice.messages.send(roomA, Draft { text = "ping" })
            assertTrue(bob.rooms.watchRooms().first { it.firstOrNull() == roomA }.isNotEmpty())

            // Opening room A for the first time loads "ping" from the store.
            val loaded = bob.messages.watchMessages(roomA).first { list -> list.any { it.payload.component?.text == "ping" } }
            assertTrue(loaded.any { it.payload.component?.text == "ping" })

            bob.close()
            alice.close()
        }

    @Test(timeout = 60_000)
    fun `sending a message bumps the sender's own room to the front at send time`() =
        runTestWithServer(Application::attachTestServices) { server, _ ->
            val alice = newParty(server.rpcClient)
            alice.myProfiles.createProfile("Alice")

            val roomA = alice.rooms.createGroup("A")
            val roomB = alice.rooms.createGroup("B")
            // B was created last, so it starts at the front of Alice's room list.
            alice.rooms.watchRooms().first { it.firstOrNull() == roomB }

            // Sending into A bumps it to the front of Alice's own list at send time — the message need
            // not be delivered for this to happen.
            alice.messages.send(roomA, Draft { text = "hi" })
            assertTrue(alice.rooms.watchRooms().first { it.firstOrNull() == roomA }.isNotEmpty())

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
            bob.rooms.joinByCode(alice.rooms.createInviteCode(roomId))
            bob.rooms.watchRooms().first { it.contains(roomId) }

            // Alice (a member, so the room lock lets her write) posts a message on the MESSAGING locker
            // that CLAIMS Bob as the sender but is signed by an unrelated key — it won't verify.
            val forger = Secp256r1KeyPair.generate()
            val forgedPayload = MessagePayload(
                roomId = roomId.rawValue,
                senderProfileId = bobProfile.rawValue,
                sentAtMillis = 1L,
                component = Component { contents.text { text = "forged" } },
                messageId = Random.nextBytes(32),
            )
            val forged = sign(forger, MessageSigning.LABEL, forgedPayload.toByteArray())
            alice.lockers.typed(MessagesKeyspaces.MESSAGING, SignedContent::toByteArray, SignedContent.Companion::fromByteArray)
                .updateLocker(
                    roomId, MessagesKeyspaces.MESSAGING_LOCKER,
                    notificationBuilder = { payload { rawValue = forged.toByteArray() } },
                ) { it }

            // A genuine message posted after it; once it arrives the forged one has been processed.
            alice.messages.send(roomId, Draft { text = "genuine" })
            val delivered = bob.messages.watchMessages(roomId).first { list -> list.any { it.payload.component?.text == "genuine" } }
            assertTrue(delivered.none { it.payload.component?.text == "forged" })

            // A non-member cannot write the MESSAGING locker at all: it is under the room lock.
            val carol = newParty(server.rpcClient)
            val carolMessages = carol.lockers.typed(
                MessagesKeyspaces.MESSAGING, SignedContent::toByteArray, SignedContent.Companion::fromByteArray,
            )
            carolMessages.subscribeToRoom(roomId)
            assertFailsWith<LockerWriteException> {
                carolMessages.updateLocker(
                    roomId, MessagesKeyspaces.MESSAGING_LOCKER,
                    notificationBuilder = { payload { rawValue = forged.toByteArray() } },
                ) { it }
            }

            carol.close()
            bob.close()
            alice.close()
        }

    @Test(timeout = 60_000)
    fun `an own message shows sending immediately then sent once delivered`() =
        runTestWithServer(Application::attachTestServices) { server, _ ->
            val alice = newParty(server.rpcClient)
            alice.myProfiles.createProfile("Alice")
            val roomId = alice.rooms.createGroup("Team")
            alice.rooms.watchRooms().first { it.contains(roomId) }

            // Optimistic local echo: the message is stored and observable the moment send() returns.
            alice.messages.send(roomId, Draft { text = "mine" })
            assertTrue(alice.messages.watchMessages(roomId).first().any { it.payload.component?.text == "mine" })

            // It flips to SENT once the outbox delivers it, and our own echoed notification does not
            // duplicate it.
            alice.messages.watchMessages(roomId).first { list ->
                list.any { it.payload.component?.text == "mine" && it.status == MessageDeliveryStatus.MESSAGE_DELIVERY_STATUS_SENT }
            }
            assertEquals(1, alice.messages.watchMessages(roomId).first().count { it.payload.component?.text == "mine" })

            alice.close()
        }

    @Test(timeout = 60_000)
    fun `an undeliverable send is dead-lettered, then delivered after retry`() =
        runTestWithServer(Application::attachTestServices) { server, _ ->
            // Withhold the write key for the MESSAGING keyspace so message writes are unsigned and the
            // server rejects them (SIGNATURE_REQUIRED) — a terminal failure. One attempt → dead-letter.
            lateinit var writes: ToggleMessagesKeySource
            val alice = newParty(server.rpcClient, maxAttempts = 1) { base ->
                ToggleMessagesKeySource(base, block = true).also { writes = it }
            }
            alice.myProfiles.createProfile("Alice")
            val roomId = alice.rooms.createGroup("Team")
            alice.rooms.watchRooms().first { it.contains(roomId) }

            // The blocked write exhausts its one attempt and the message is dead-lettered as FAILED.
            alice.messages.send(roomId, Draft { text = "will fail" })
            alice.messages.watchMessages(roomId).first { list ->
                list.any { it.payload.component?.text == "will fail" && it.status == MessageDeliveryStatus.MESSAGE_DELIVERY_STATUS_FAILED }
            }
            val messageId = alice.messages.watchMessageIds(roomId).first().first()

            // Unblock writes and retry: the re-queued message now delivers and flips to SENT.
            writes.block = false
            alice.messages.retry(roomId, messageId)
            alice.messages.watchMessages(roomId).first { list ->
                list.any { it.payload.component?.text == "will fail" && it.status == MessageDeliveryStatus.MESSAGE_DELIVERY_STATUS_SENT }
            }

            alice.close()
        }

    @Test(timeout = 60_000)
    fun `concurrent sends to the shared messaging locker are both delivered`() =
        runTestWithServer(Application::attachTestServices) { server, _ ->
            val alice = newParty(server.rpcClient)
            val bob = newParty(server.rpcClient)
            alice.myProfiles.createProfile("Alice")
            bob.myProfiles.createProfile("Bob")

            val roomId = alice.rooms.createGroup("Team")
            bob.rooms.joinByCode(alice.rooms.createInviteCode(roomId))
            bob.rooms.watchRooms().first { it.contains(roomId) }

            // Both members write the single MESSAGING locker at once; the version bump under the room
            // lock linearizes them (the connector retries the loser), so both land.
            coroutineScope {
                launch { alice.messages.send(roomId, Draft { text = "from-alice" }) }
                launch { bob.messages.send(roomId, Draft { text = "from-bob" }) }
            }

            bob.messages.watchMessages(roomId).first { list ->
                list.any { it.payload.component?.text == "from-alice" } && list.any { it.payload.component?.text == "from-bob" }
            }

            bob.close()
            alice.close()
        }

    @Test(timeout = 60_000)
    fun `a draft with attachments and text fans out into one message per component`() =
        runTestWithServer(Application::attachTestServices) { server, _ ->
            val alice = newParty(server.rpcClient)
            val bob = newParty(server.rpcClient)
            alice.myProfiles.createProfile("Alice")
            val bobProfile = bob.myProfiles.createProfile("Bob")

            val roomId = alice.rooms.createGroup("Team")
            bob.rooms.joinByCode(alice.rooms.createInviteCode(roomId))
            bob.rooms.watchRooms().first { it.contains(roomId) }

            fun photo(seed: Byte, url: String) = DraftAttachment {
                contentId = ByteArray(16) { seed }
                component = Component { contents.image { image { this.url = url } } }
            }
            alice.messages.send(roomId, Draft {
                text = "look at these"
                attachments = listOf(photo(1, "photo-1"), photo(2, "photo-2"))
            })

            // The draft fans out into three messages: two image components and one text component.
            val delivered = bob.messages.watchMessages(roomId).first { it.size == 3 }
            val urls = delivered.mapNotNull {
                (it.payload.component?.contents as? Component.OneOfContents.image)?.value?.image?.url
            }
            assertEquals(setOf("photo-1", "photo-2"), urls.toSet())
            assertTrue(delivered.any { it.payload.component?.text == "look at these" })

            bob.close()
            alice.close()
        }
}

// A lock key source that can withhold the write key for the MESSAGING keyspace, forcing message
// writes to be unsigned so the server rejects them (SIGNATURE_REQUIRED). Room setup (other keyspaces)
// keeps its key, so only message delivery is blocked; flip [block] to restore delivery.
private class ToggleMessagesKeySource(
    private val delegate: LockKeySource,
    @Volatile var block: Boolean,
) : LockKeySource {
    override suspend fun writeKeyFor(roomId: RoomId, lockerId: LockerId): Secp256r1KeyPair? =
        if (block && lockerId.keyspace?.value == MessagesKeyspaces.MESSAGING.value) null
        else delegate.writeKeyFor(roomId, lockerId)

    override suspend fun onRatcheted(roomId: RoomId, lockerId: LockerId, newKeyPair: Secp256r1KeyPair) =
        delegate.onRatcheted(roomId, lockerId, newKeyPair)
}

// Shared in-process stand-in for the server-side Join service, so a two-member group can be
// assembled in tests without a running JoinService (its real logic is covered by rooms-service).
private val sharedJoinClient = FakeJoinClient()

private class FakeJoinClient : JoinClient {
    private class Stored(val roomId: ByteArray, val groupPrivateKey: ByteArray)

    private val codes = mutableMapOf<List<Byte>, Stored>()

    override suspend fun createInviteCode(request: CreateInviteCodeRequest): CreateInviteCodeResponse {
        if (!keyMatchesRoom(request.groupPrivateKey, request.roomId)) {
            return CreateInviteCodeResponse { result = JoinResult.JOIN_RESULT_UNAUTHORIZED }
        }
        val code = Random.nextBytes(32)
        codes[code.toList()] = Stored(request.roomId, request.groupPrivateKey)
        return CreateInviteCodeResponse {
            result = JoinResult.JOIN_RESULT_OK
            this.code = InviteCode { value = code }
        }
    }

    override suspend fun join(request: JoinRequest): JoinResponse {
        val stored = codes[request.code?.value?.toList()]
            ?: return JoinResponse { result = JoinResult.JOIN_RESULT_INVALID_CODE }
        val invite = Invite {
            kind = RoomKind.ROOM_KIND_GROUP
            roomId = stored.roomId
            groupPrivateKey = stored.groupPrivateKey
        }
        return JoinResponse {
            result = JoinResult.JOIN_RESULT_OK
            sealedInvite = Sealing.seal(request.inviteeProfileId, invite.toByteArray())
        }
    }

    override suspend fun revokeInviteCode(request: RevokeInviteCodeRequest): RevokeInviteCodeResponse {
        val key = request.code?.value?.toList()
        val stored = key?.let { codes[it] }
            ?: return RevokeInviteCodeResponse { result = JoinResult.JOIN_RESULT_INVALID_CODE }
        if (!keyMatchesRoom(request.groupPrivateKey, stored.roomId)) {
            return RevokeInviteCodeResponse { result = JoinResult.JOIN_RESULT_UNAUTHORIZED }
        }
        codes.remove(key)
        return RevokeInviteCodeResponse { result = JoinResult.JOIN_RESULT_OK }
    }

    private suspend fun keyMatchesRoom(privateKey: ByteArray, roomId: ByteArray): Boolean {
        val keyPair = Secp256r1KeyPair.fromPrivateKey(privateKey) ?: return false
        return RoomKeying.publicKeyed(keyPair.publicKey.encode()).rawValue.contentEquals(roomId)
    }
}
