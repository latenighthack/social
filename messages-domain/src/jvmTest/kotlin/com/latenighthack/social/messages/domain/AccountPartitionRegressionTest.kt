package com.latenighthack.social.messages.domain

import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.social.runtime.AccountSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import com.latenighthack.ktbuf.test.server.runTestWithServer
import com.latenighthack.ktstore.*
import com.latenighthack.lockers.connector.*
import com.latenighthack.lockers.common.v1.Version
import com.latenighthack.lockers.server.*
import com.latenighthack.social.account.domain.AccountManagerImpl
import com.latenighthack.social.account.domain.AccountKeySource
import com.latenighthack.social.messages.v1.*
import io.ktor.server.application.Application
import kotlin.test.Test
import kotlin.test.assertEquals

class AccountPartitionRegressionTest {
    @Test(timeout = 30000) fun accountsInTheSameRoomRetainIndependentDrafts() = runTestWithServer(Application::attachTestServices) { server, _ ->
        val account = AccountManagerImpl(KeyValueStore(InMemoryKeyValueStoreDelegate()))
        val client = LockersClient.create(rpcClient = server.rpcClient,
            database = Database(ConnectorStorage.configuration("partition-connector"), InMemoryStoreDelegate()),
            keyValueStore = KeyValueStore(InMemoryKeyValueStoreDelegate()), keySource = AccountKeySource(account), appVersion = Version(0, 0, 1))
        val database = MessagesStorage.inMemory("account-partition")
        database.open()
        val session = object : AccountSession { override val owner = MutableStateFlow<String?>("alice") }
        val drafts = DraftsManagerImpl(database, session = session)
        try {
            drafts.prepare(); drafts.start(client)
            val room = RoomId(rawValue = byteArrayOf(1))
            drafts.setText(room, "Alice's draft")
            session.owner.value = "bob"
            drafts.setText(room, "Bob's draft")
            drafts.stopAndJoin()
            session.owner.value = "alice"
            val restored = DraftsManagerImpl(database, session = session)
            restored.prepare(); restored.start(client)
            assertEquals("Alice's draft", withTimeout(5000) { restored.watchDraft(room).first { it != null } }?.text)
            restored.stopAndJoin()
        } finally { drafts.stopAndJoin(); client.close(); database.close() }
    }

    @Test fun historiesAndQueuesPartitionTheSameMessageIdByAccount() = runBlocking {
        val database = MessagesStorage.inMemory("partition-messages"); database.open()
        try {
            val messages = MessageStore(database); val pending = PendingMessageStore(database); val dead = DeadLetterStore(database)
            messages.prepare(); pending.prepare(); dead.prepare()
            val room = RoomId(rawValue = byteArrayOf(1)); val id = MessageId(rawValue = byteArrayOf(2))
            for (owner in listOf("alice", "bob")) {
                messages.saveMessage(LocalMessage(roomId = room.rawValue, messageId = id, ownerAccountId = owner))
                val row = PendingMessage(roomId = room.rawValue, messageId = id, ownerAccountId = owner)
                pending.savePending(row); dead.saveDeadLettered(row)
            }
            pending.deletePending(room, id, "bob"); dead.deleteDeadLettered(room, id, "bob"); messages.deleteMessage(room, id, "bob")
            assertEquals("alice", messages.getMessage(room, id, "alice")?.ownerAccountId)
            assertEquals("alice", pending.getAllPending().single().ownerAccountId)
            assertEquals("alice", dead.getDeadLettered(room, id, "alice")?.ownerAccountId)
        } finally { database.close() }
    }
    @Test fun revokingTheSameIdentityCancelsADraftCommandWaitingForStartup() = runBlocking {
        val database = MessagesStorage.inMemory("queued-draft-owner"); database.open()
        val session = object : AccountSession {
            override val owner = MutableStateFlow<String?>("alice")
            override val generation = MutableStateFlow(0L)
        }
        val drafts = DraftsManagerImpl(database, session = session)
        val command = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            drafts.setText(RoomId(rawValue = byteArrayOf(1)), "withdrawn")
        }
        try {
            session.generation.value++
            withTimeout(5000) { command.join() }
            kotlin.test.assertTrue(command.isCancelled)
            kotlin.test.assertTrue(DraftStore(database).getAllDrafts().isEmpty())
        } finally { command.cancel(); drafts.stopAndJoin(); database.close() }
    }
}
