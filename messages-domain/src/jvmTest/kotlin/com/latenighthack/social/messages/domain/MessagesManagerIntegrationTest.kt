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
import kotlinx.coroutines.flow.first
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
    ): Party {
        val account = AccountManagerImpl(accountStore)
        val accountKeySource = AccountKeySource(account)
        val myProfiles = MyProfilesManagerImpl(account)
        val profileKeySource = ProfileKeySource(myProfiles, accountKeySource)
        val rooms = RoomsManagerImpl(account, myProfiles, joinClient)
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
            bob.rooms.joinByCode(alice.rooms.createInviteCode(roomId))
            bob.rooms.watchRooms().first { it.contains(roomId) }

            alice.messages.send(roomId, Draft { text = "hello bob" })

            val delivered = bob.messages.watchMessages(roomId).first { list -> list.any { it.component?.text == "hello bob" } }
            val message = delivered.first { it.component?.text == "hello bob" }
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
            bob.messages.watchMessages(roomId).first { list -> list.any { it.component?.text == "hi bob" } }

            bob.messages.send(roomId, Draft { text = "hi alice" })
            alice.messages.watchMessages(roomId).first { list -> list.any { it.component?.text == "hi alice" } }

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
            bob.rooms.joinByCode(alice.rooms.createInviteCode(roomId))
            bob.rooms.watchRooms().first { it.contains(roomId) }

            // Alice (a member, so the room lock lets her write) posts a message that CLAIMS Bob as the
            // sender but is signed by an unrelated key — the signature won't match bobProfile.
            val forger = Secp256r1KeyPair.generate()
            val forgedPayload = MessagePayload(
                roomId = roomId.rawValue,
                senderProfileId = bobProfile.rawValue,
                sentAtMillis = 1L,
                component = Component { contents.text { text = "forged" } },
            )
            val forged = sign(forger, MessageSigning.LABEL, forgedPayload.toByteArray())
            alice.lockers.typed(MessagesKeyspaces.MESSAGES, SignedContent::toByteArray, SignedContent.Companion::fromByteArray)
                .updateLocker(roomId, LockerId(Random.nextBytes(32), MessagesKeyspaces.MESSAGES)) { forged }

            // A genuine message posted after it; once it arrives the forged one has been processed.
            alice.messages.send(roomId, Draft { text = "genuine" })
            val delivered = bob.messages.watchMessages(roomId).first { list -> list.any { it.component?.text == "genuine" } }
            assertTrue(delivered.none { it.component?.text == "forged" })

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
            device1.messages.watchMessages(roomId).first { list -> list.any { it.component?.text == "note to self" } }
            device1.close()

            // A fresh device restoring the same account recovers the room and re-syncs the message.
            val device2 = newParty(server.rpcClient, accountStore)
            device2.messages.watchMessages(roomId).first { list -> list.any { it.component?.text == "note to self" } }

            device2.close()
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
                (it.component?.contents as? Component.OneOfContents.image)?.value?.image?.url
            }
            assertEquals(setOf("photo-1", "photo-2"), urls.toSet())
            assertTrue(delivered.any { it.component?.text == "look at these" })

            bob.close()
            alice.close()
        }
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
