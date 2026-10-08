package com.latenighthack.social.messages.domain

import com.latenighthack.ktbuf.test.server.runTestWithServer
import com.latenighthack.ktstore.*
import com.latenighthack.lockers.common.v1.*
import com.latenighthack.lockers.connector.*
import com.latenighthack.lockers.server.*
import com.latenighthack.social.profiles.domain.*
import com.latenighthack.social.account.domain.*
import io.ktor.server.application.Application
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

class DraftConcurrencyRegressionTest {
    @Test(timeout=30000) fun concurrentSavesMustAgreeWithObservedDraft() = runTestWithServer(Application::attachTestServices) { server, _ ->
        val account = AccountManagerImpl(KeyValueStore(InMemoryKeyValueStoreDelegate()))
        val client = LockersClient.create(rpcClient=server.rpcClient,
            database=Database(ConnectorStorage.configuration("review-draft-connector"), InMemoryStoreDelegate()),
            keyValueStore=KeyValueStore(InMemoryKeyValueStoreDelegate()), keySource=AccountKeySource(account), appVersion=Version(0,0,1))
        val base = InMemoryStoreDelegate()
        val firstSaving = CompletableDeferred<Unit>()
        val finishFirst = CompletableDeferred<Unit>()
        var writes = 0
        val delegate = object : LifecycleStoreDelegate by base {
            override suspend fun save(tableName: String, data: Any, keys: List<BoundStoreKey>) {
                if (tableName == "drafts" && ++writes == 1) { firstSaving.complete(Unit); finishFirst.await() }
                base.save(tableName, data, keys)
            }
        }
        val db = Database(MessagesStorage.configuration("review-drafts"), delegate)
        db.open()
        val drafts = DraftsManagerImpl(db)
        drafts.prepare(); drafts.start(client)
        val room = RoomId(rawValue=byteArrayOf(1))
        try {
            coroutineScope {
                val first = launch { drafts.setText(room, "first") }
                firstSaving.await()
                val second = launch { drafts.setText(room, "second") }
                finishFirst.complete(Unit)
                first.join()
                second.join()
            }
            assertEquals(drafts.watchDraft(room).first()?.text, DraftStore(db).getAllDrafts().single().draft?.text,
                "observable draft and durable draft disagree")
            val sent = drafts.watchDraft(room).first()!!
            drafts.setText(room, "new edit during send")
            drafts.clearIfUnchanged(room, sent)
            assertEquals("new edit during send", drafts.watchDraft(room).first()?.text)
        } finally { drafts.stop(); client.close() }
    }
}
