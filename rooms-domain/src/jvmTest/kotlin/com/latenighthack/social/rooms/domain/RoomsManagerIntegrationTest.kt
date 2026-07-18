package com.latenighthack.social.rooms.domain

import com.latenighthack.ktbuf.net.RpcClient
import com.latenighthack.ktbuf.test.server.runTestWithServer
import com.latenighthack.ktcrypto.SHA256
import com.latenighthack.ktcrypto.Secp256r1KeyPair
import com.latenighthack.ktcrypto.digest
import com.latenighthack.ktcrypto.encode
import com.latenighthack.ktcrypto.fromPrivateKey
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
import com.latenighthack.social.common.domain.Sealing
import com.latenighthack.social.common.v1.SealedEnvelope
import com.latenighthack.social.profiles.domain.MyProfilesManagerImpl
import com.latenighthack.social.profiles.domain.ProfileKeySource
import com.latenighthack.social.profiles.v1.ProfileId
import com.latenighthack.social.rooms.v1.CreateInviteCodeRequest
import com.latenighthack.social.rooms.v1.CreateInviteCodeResponse
import com.latenighthack.social.rooms.v1.Invite
import com.latenighthack.social.rooms.v1.InviteCode
import com.latenighthack.social.rooms.v1.JoinRequest
import com.latenighthack.social.rooms.v1.JoinResponse
import com.latenighthack.social.rooms.v1.JoinResult
import com.latenighthack.social.rooms.v1.Member
import com.latenighthack.social.rooms.v1.RevokeInviteCodeRequest
import com.latenighthack.social.rooms.v1.RevokeInviteCodeResponse
import com.latenighthack.social.rooms.v1.RoomKind
import com.latenighthack.social.common.v1.fromByteArray
import com.latenighthack.social.common.v1.toByteArray
import com.latenighthack.social.rooms.v1.fromByteArray
import com.latenighthack.social.rooms.v1.toByteArray
import io.ktor.server.application.Application
import kotlinx.coroutines.flow.first
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
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
        joinClient: JoinClient = FakeJoinClient(),
    ): Party {
        val account = AccountManagerImpl(accountStore)
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
    fun `an invite code grants group access and a revoked code cannot`() =
        runTestWithServer(Application::attachTestServices) { server, _ ->
            val join = FakeJoinClient()
            val alice = newParty(server.rpcClient, joinClient = join)
            val bob = newParty(server.rpcClient, joinClient = join)
            val aliceProfile = alice.myProfiles.createProfile("Alice")
            val bobProfile = bob.myProfiles.createProfile("Bob")

            val roomId = alice.rooms.createGroup("Team")
            val code = alice.rooms.createInviteCode(roomId)

            // Bob redeems the code: the server seals the group grant to his profile, he unseals it,
            // adopts the key, and writes his own membership.
            assertEquals(roomId, bob.rooms.joinByCode(code))
            assertTrue(bob.rooms.watchRooms().first { it.contains(roomId) }.isNotEmpty())

            // Both members see the room as a group; a non-member sees no kind.
            assertEquals(RoomKind.ROOM_KIND_GROUP, alice.rooms.roomKind(roomId))
            assertEquals(RoomKind.ROOM_KIND_GROUP, bob.rooms.roomKind(roomId))

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
            assertNull(carol.rooms.roomKind(roomId))
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

            // Revoking the code stops a later joiner: the server no longer honors it.
            alice.rooms.revokeInviteCode(roomId, code)
            val dave = newParty(server.rpcClient, joinClient = join)
            dave.myProfiles.createProfile("Dave")
            assertFailsWith<IllegalStateException> { dave.rooms.joinByCode(code) }

            // Leaving removes Bob's own roster entry.
            bob.rooms.leave(roomId)
            assertEquals(listOf(aliceProfile), alice.rooms.watchMembers(roomId).first { it.size == 1 })

            dave.close()
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

            // Both are members of the 1:1 room, and both see it as a rendezvous.
            assertEquals(2, alice.rooms.watchMembers(roomId).first { it.size == 2 }.size)
            assertEquals(RoomKind.ROOM_KIND_RENDEZVOUS, alice.rooms.roomKind(roomId))
            assertEquals(RoomKind.ROOM_KIND_RENDEZVOUS, bob.rooms.roomKind(roomId))

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
            val envelope = Sealing.seal(bobProfile.rawValue, byteArrayOf(1, 2, 3))
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

/**
 * In-process stand-in for the server-side Join service, contract-faithful for the client paths under
 * test (key↔room check on create/revoke, seal the group grant to the joiner on join). The real
 * server logic — expiry, use limits, allowed-profile — is covered by rooms-service's own tests.
 */
internal class FakeJoinClient : JoinClient {
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
