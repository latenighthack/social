package com.latenighthack.social.rooms.domain

import com.latenighthack.ktbuf.net.RpcClient
import com.latenighthack.ktbuf.net.RpcMethodSpecifier
import com.latenighthack.ktbuf.net.RpcResponse
import com.latenighthack.ktbuf.net.RpcServerStream
import com.latenighthack.ktbuf.test.server.runTestWithServer
import com.latenighthack.ktstore.InMemoryKeyValueStoreDelegate
import com.latenighthack.ktstore.KeyValueStore
import com.latenighthack.lockers.common.v1.Version
import com.latenighthack.lockers.connector.LockersClient
import com.latenighthack.lockers.server.attachFastpathTestServices
import com.latenighthack.lockers.server.rpcClient
import com.latenighthack.social.account.domain.AccountKeySource
import com.latenighthack.social.account.domain.AccountManager
import com.latenighthack.social.account.domain.AccountManagerImpl
import com.latenighthack.social.profiles.domain.MyProfilesManagerImpl
import com.latenighthack.social.profiles.domain.ProfileKeySource
import io.ktor.server.application.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import com.latenighthack.lockers.common.v1.LockerId
import com.latenighthack.social.rooms.v1.RoomRecord
import com.latenighthack.social.rooms.v1.copy
import com.latenighthack.social.rooms.v1.fromByteArray
import com.latenighthack.social.rooms.v1.toByteArray
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * Records unary RPC method names. Optionally adds a fixed real-time latency to each call, or holds
 * every call until [gate] completes (to prove a command returns without the network).
 */
internal class CountingRpcClient(private val delegate: RpcClient) : RpcClient {
    @Volatile var latencyMillis = 0L
    @Volatile var gate: CompletableDeferred<Unit>? = null
    private val calls = java.util.Collections.synchronizedList(mutableListOf<String>())

    fun reset() = calls.clear()
    fun calls(): List<String> = synchronized(calls) { calls.toList() }

    override suspend fun unaryCall(method: RpcMethodSpecifier, headers: Map<String, String>, request: ByteArray): RpcResponse {
        gate?.await()
        calls.add(method.methodName)
        if (latencyMillis > 0) withContext(Dispatchers.Default) { delay(latencyMillis) }
        return delegate.unaryCall(method, headers, request)
    }

    override suspend fun serverStreamingCall(method: RpcMethodSpecifier, block: suspend RpcServerStream.() -> Unit, readyCallback: () -> Unit) =
        delegate.serverStreamingCall(method, block, readyCallback)
}

class CreateGroupRoundTripTest {

    private class Party(
        val rpc: CountingRpcClient,
        val account: AccountManagerImpl,
        val myProfiles: MyProfilesManagerImpl,
        val rooms: RoomsManagerImpl,
        val lockers: LockersClient,
        val database: com.latenighthack.ktstore.Database,
    ) {
        fun close() { rooms.stop(); myProfiles.stop(); account.stop(); lockers.close() }

        suspend fun accountRecord(room: com.latenighthack.lockers.common.v1.RoomId): RoomRecord? =
            lockers.typed(RoomsKeyspaces.ACCOUNT_ROOMS, RoomRecord::toByteArray, RoomRecord.Companion::fromByteArray)
                .getLocker(account.localAccountRoom()!!, LockerId(room.rawValue, RoomsKeyspaces.ACCOUNT_ROOMS))

        suspend fun pendingRows() = PendingRoomStore(database).let { it.prepare(); it.pendingFor(account.owner.value!!) }
    }

    private suspend fun newParty(rpcClient: RpcClient): Party {
        val rpc = CountingRpcClient(rpcClient)
        val account = AccountManagerImpl(KeyValueStore(InMemoryKeyValueStoreDelegate()))
        val accountKeySource = AccountKeySource(account)
        val myProfiles = MyProfilesManagerImpl(account)
        val database = com.latenighthack.ktstore.Database(com.latenighthack.lockers.connector.ConnectorStorage.configuration("rt-${kotlin.random.Random.nextLong()}", RoomsStorage.definitions), com.latenighthack.ktstore.InMemoryStoreDelegate())
        val rooms = RoomsManagerImpl(account, myProfiles, FakeJoinClient(), database)
        rooms.prepare()
        val lockers = LockersClient.create(
            rpcClient = rpc,
            database = database,
            keyValueStore = KeyValueStore(InMemoryKeyValueStoreDelegate()),
            keySource = accountKeySource,
            appVersion = Version(0, 0, 1),
            lockKeySource = RoomsKeySource(rooms, ProfileKeySource(myProfiles, accountKeySource)),
        )
        account.start(lockers); myProfiles.start(lockers); rooms.start(lockers)
        account.createAccount()
        account.lifecycle.first { it is AccountManager.Lifecycle.Ready }
        return Party(rpc, account, myProfiles, rooms, lockers, database)
    }

    private suspend fun settle() = withContext(Dispatchers.Default) { delay(1000) }

    /** Wait (in real time) until no local create is still pending server setup. */
    private suspend fun Party.awaitSetUp() = withContext(Dispatchers.Default) {
        kotlinx.coroutines.withTimeout(10_000) { while (pendingRows().isNotEmpty()) delay(20) }
    }

    @Test(timeout = 60_000)
    fun `createGroup returns without the network and sets the room up in one lock-and-write batch`() =
        runTestWithServer(Application::attachFastpathTestServices) { server, _ ->
            val party = newParty(server.rpcClient)
            try {
                val me = party.myProfiles.createProfile("Alice")
                // Warm-up room so one-time costs (capabilities, account-room subscription) aren't counted.
                party.rooms.createGroup("warm")
                party.awaitSetUp()
                settle()

                party.rpc.reset()
                val gate = CompletableDeferred<Unit>().also { party.rpc.gate = it }
                val room = withContext(Dispatchers.Default) { kotlinx.coroutines.withTimeout(5000) { party.rooms.createGroup("Team") } }
                assertEquals(party.myProfiles.getProfileList().first().first(), party.rooms.localProfile(room))
                // Listed and readable straight away, before the server has seen the room.
                assertTrue(room in party.rooms.watchRooms().first())
                assertEquals("Team", withContext(Dispatchers.Default) { kotlinx.coroutines.withTimeout(5000) { party.rooms.watchInfo(room).first() } }?.name())
                assertEquals(setOf(room), party.rooms.watchUnacknowledgedRooms().first())
                party.rpc.latencyMillis = 120
                val start = TimeSource.Monotonic.markNow()
                gate.complete(Unit)
                party.awaitSetUp()
                val ready = start.elapsedNow()
                assertTrue(me in party.rooms.watchMembers(room).first { me in it })
                assertEquals("Team", party.rooms.watchInfo(room).first { it?.name() == "Team" }?.name())
                assertTrue(party.rooms.watchUnacknowledgedRooms().first().isEmpty())
                settle()

                val calls = party.rpc.calls()
                println("CREATE-RT ready=${ready.inWholeMilliseconds}ms calls=$calls")
                assertEquals(0, calls.count { it == "LockLocker" }, "$calls")
                assertEquals(0, calls.count { it == "GetLockers" }, "$calls")
                assertEquals(1, calls.count { it == "PostLockerChanges" }, "$calls")
                assertEquals(1, calls.count { it == "PostLockerChange" }, "$calls")
                assertFalse(party.accountRecord(room)!!.membershipPending)
                assertTrue(party.pendingRows().isEmpty())
            } finally { party.close() }
        }

    @Test(timeout = 60_000)
    fun `a create interrupted before reaching the server completes on the next start`() =
        runTestWithServer(Application::attachFastpathTestServices) { server, _ ->
            val party = newParty(server.rpcClient)
            try {
                val me = party.myProfiles.createProfile("Alice")
                party.rpc.gate = CompletableDeferred()
                val room = withContext(Dispatchers.Default) { kotlinx.coroutines.withTimeout(5000) { party.rooms.createGroup("Team") } }
                // The "crash": the manager stops before any setup write reaches the server.
                party.rooms.stopAndJoin()
                assertEquals(1, party.pendingRows().size)
                party.rpc.gate!!.complete(Unit)

                party.rooms.start(party.lockers)
                party.rooms.watchRooms().first { room in it }
                party.awaitSetUp()
                assertTrue(me in party.rooms.watchMembers(room).first { me in it })
                assertEquals("Team", party.rooms.watchInfo(room).first { it?.name() == "Team" }?.name())
                assertFalse(party.accountRecord(room)!!.membershipPending)
                assertTrue(party.pendingRows().isEmpty())
            } finally { party.rpc.gate?.complete(Unit); party.close() }
        }

    @Test(timeout = 60_000)
    fun `a setup that already reached the server resumes without failing`() =
        runTestWithServer(Application::attachFastpathTestServices) { server, _ ->
            val party = newParty(server.rpcClient)
            try {
                val me = party.myProfiles.createProfile("Alice")
                val room = party.rooms.createGroup("Team")
                party.awaitSetUp()
                party.rooms.stopAndJoin()
                // As if the app died after the server writes but before the local row was dropped.
                val record = party.accountRecord(room)!!
                PendingRoomStore(party.database).also { it.prepare() }.savePending(
                    com.latenighthack.social.rooms.v1.LocalPendingRoom(roomId = room.rawValue, ownerAccountId = party.account.owner.value!!,
                        record = record.copy { membershipPending = true }))
                party.rooms.start(party.lockers)
                party.rooms.watchRooms().first { room in it }
                party.awaitSetUp()
                assertTrue(me in party.rooms.watchMembers(room).first())
                assertFalse(party.accountRecord(room)!!.membershipPending)
            } finally { party.close() }
        }

    @Test(timeout = 60_000)
    fun `leaving during setup cancels it and leaves no membership`() =
        runTestWithServer(Application::attachFastpathTestServices) { server, _ ->
            val party = newParty(server.rpcClient)
            try {
                party.myProfiles.createProfile("Alice")
                val gate = CompletableDeferred<Unit>().also { party.rpc.gate = it }
                val room = withContext(Dispatchers.Default) { kotlinx.coroutines.withTimeout(5000) { party.rooms.createGroup("Team") } }
                assertTrue(room in party.rooms.watchRooms().first())
                val leaving = async { party.rooms.leave(room) }
                withContext(Dispatchers.Default) { delay(200) }
                gate.complete(Unit)
                leaving.await()
                assertFalse(room in party.rooms.watchRooms().first())
                assertTrue(party.pendingRows().isEmpty())
                assertTrue(party.rooms.watchMembers(room).first().isEmpty())
                assertTrue(party.accountRecord(room)?.left != false)
            } finally { party.close() }
        }
}
