package com.latenighthack.social.typing.domain

import com.latenighthack.ktbuf.net.RpcClient
import com.latenighthack.ktbuf.test.server.runTestWithServer
import com.latenighthack.ktcrypto.Secp256r1KeyPair
import com.latenighthack.ktcrypto.encode
import com.latenighthack.ktcrypto.fromPrivateKey
import com.latenighthack.lockers.common.RoomKeying
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
import com.latenighthack.social.common.domain.Sealing
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

class TypingManagerIntegrationTest {

    private class Party(
        val myProfiles: MyProfilesManagerImpl,
        val rooms: RoomsManagerImpl,
        val typing: TypingManagerImpl,
        val lockers: LockersClient,
    ) {
        fun close() {
            typing.stop()
            rooms.stop()
            myProfiles.stop()
            lockers.close()
        }
    }

    // A long timeout keeps a typing member alive across test steps (so only an explicit stop clears
    // them); tests exercising the timeout pass a short one.
    private suspend fun newParty(
        rpcClient: RpcClient,
        debounceMillis: Long = 100,
        timeoutMillis: Long = 10_000,
        tickMillis: Long = 30,
        joinClient: JoinClient = sharedJoinClient,
    ): Party {
        val account = AccountManagerImpl(KeyValueStore(InMemoryKeyValueStoreDelegate()))
        val accountKeySource = AccountKeySource(account)
        val myProfiles = MyProfilesManagerImpl(account)
        val profileKeySource = ProfileKeySource(myProfiles, accountKeySource)
        val rooms = RoomsManagerImpl(account, myProfiles, joinClient)
        val roomsKeySource = RoomsKeySource(rooms, profileKeySource)
        val typing = TypingManagerImpl(rooms, debounceMillis, timeoutMillis, tickMillis)
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
        typing.start(lockers)
        account.createAccount()
        account.lifecycle.first { it is AccountManager.Lifecycle.Ready }
        return Party(myProfiles, rooms, typing, lockers)
    }

    @Test(timeout = 60_000)
    fun `a member's typing is delivered to other members`() =
        runTestWithServer(Application::attachTestServices) { server, _ ->
            val alice = newParty(server.rpcClient)
            val bob = newParty(server.rpcClient)
            val aliceProfile = alice.myProfiles.createProfile("Alice")
            val bobProfile = bob.myProfiles.createProfile("Bob")

            val roomId = alice.rooms.createGroup("Team")
            bob.rooms.joinByCode(alice.rooms.createInviteCode(roomId))
            bob.rooms.watchRooms().first { it.contains(roomId) }

            alice.typing.setTyping(roomId, true)

            val typers = bob.typing.watchTyping(roomId).first { aliceProfile in it }
            assertTrue(aliceProfile in typers)

            bob.close()
            alice.close()
        }

    @Test(timeout = 60_000)
    fun `watchTyping excludes the observer's own profile`() =
        runTestWithServer(Application::attachTestServices) { server, _ ->
            val alice = newParty(server.rpcClient)
            val bob = newParty(server.rpcClient)
            val aliceProfile = alice.myProfiles.createProfile("Alice")
            val bobProfile = bob.myProfiles.createProfile("Bob")

            val roomId = alice.rooms.createGroup("Team")
            bob.rooms.joinByCode(alice.rooms.createInviteCode(roomId))
            bob.rooms.watchRooms().first { it.contains(roomId) }

            // Both type. Alice's own event echoes back to her session, so proving her watch shows Bob
            // but not herself demonstrates self-exclusion (not merely that she isn't in the map).
            alice.typing.setTyping(roomId, true)
            bob.typing.setTyping(roomId, true)

            val aliceSees = alice.typing.watchTyping(roomId).first { bobProfile in it }
            assertTrue(aliceProfile !in aliceSees)

            bob.close()
            alice.close()
        }

    @Test(timeout = 60_000)
    fun `stopping typing clears the member promptly`() =
        runTestWithServer(Application::attachTestServices) { server, _ ->
            // A long timeout means only the explicit stop can clear Alice, isolating that behavior.
            val alice = newParty(server.rpcClient)
            val bob = newParty(server.rpcClient)
            val aliceProfile = alice.myProfiles.createProfile("Alice")
            val bobProfile = bob.myProfiles.createProfile("Bob")

            val roomId = alice.rooms.createGroup("Team")
            bob.rooms.joinByCode(alice.rooms.createInviteCode(roomId))
            bob.rooms.watchRooms().first { it.contains(roomId) }

            alice.typing.setTyping(roomId, true)
            bob.typing.watchTyping(roomId).first { aliceProfile in it }

            alice.typing.setTyping(roomId, false)
            bob.typing.watchTyping(roomId).first { aliceProfile !in it }

            bob.close()
            alice.close()
        }

    @Test(timeout = 60_000)
    fun `a member times out without an explicit stop`() =
        runTestWithServer(Application::attachTestServices) { server, _ ->
            val alice = newParty(server.rpcClient, timeoutMillis = 300)
            val bob = newParty(server.rpcClient, timeoutMillis = 300)
            val aliceProfile = alice.myProfiles.createProfile("Alice")
            val bobProfile = bob.myProfiles.createProfile("Bob")

            val roomId = alice.rooms.createGroup("Team")
            bob.rooms.joinByCode(alice.rooms.createInviteCode(roomId))
            bob.rooms.watchRooms().first { it.contains(roomId) }

            alice.typing.setTyping(roomId, true)

            // See Alice typing, then — with no refresh — see the timeout prune her within one collection
            // (guarding against the flow's initial empty emission before her event arrives).
            var sawTyping = false
            bob.typing.watchTyping(roomId).first { typers ->
                if (aliceProfile in typers) sawTyping = true
                sawTyping && aliceProfile !in typers
            }

            bob.close()
            alice.close()
        }

    @Test(timeout = 60_000)
    fun `debounced refreshes keep a member typing past a single timeout`() =
        runTestWithServer(Application::attachTestServices) { server, _ ->
            // debounce (150ms) < timeout (400ms): repeated setTyping refreshes within the window keep the
            // indicator alive even though the sends are debounced, mirroring the 10s/15s production ratio.
            val alice = newParty(server.rpcClient, debounceMillis = 150, timeoutMillis = 400, tickMillis = 30)
            val bob = newParty(server.rpcClient, debounceMillis = 150, timeoutMillis = 400, tickMillis = 30)
            val aliceProfile = alice.myProfiles.createProfile("Alice")
            val bobProfile = bob.myProfiles.createProfile("Bob")

            val roomId = alice.rooms.createGroup("Team")
            bob.rooms.joinByCode(alice.rooms.createInviteCode(roomId))
            bob.rooms.watchRooms().first { it.contains(roomId) }

            // Keep typing for ~800ms (2x the timeout) with keystrokes every 80ms; debounce coalesces them.
            repeat(10) {
                alice.typing.setTyping(roomId, true)
                delay(80)
            }

            val typers = bob.typing.watchTyping(roomId).first { aliceProfile in it }
            assertTrue(aliceProfile in typers)

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
