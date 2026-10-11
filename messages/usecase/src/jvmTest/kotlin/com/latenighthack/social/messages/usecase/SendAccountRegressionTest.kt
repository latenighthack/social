package com.latenighthack.social.messages.usecase

import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.lockers.connector.LockersClient
import com.latenighthack.social.messages.domain.*
import com.latenighthack.social.messages.v1.*
import com.latenighthack.social.runtime.AccountSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SendAccountRegressionTest {
    @Test fun accountSwapAfterEnqueueCannotClearAnotherAccountsIdenticalDraft() = runTest {
        val session = object : AccountSession { override val owner = MutableStateFlow<String?>("alice") }
        var cleared = false
        val messages = object : MessagesManager {
            override fun start(lockers: LockersClient) = Unit
            override fun stop() = Unit
            override suspend fun send(roomId: RoomId, draft: Draft) { session.owner.value = "bob" }
            override suspend fun retry(roomId: RoomId, messageId: MessageId) = Unit
            override fun watchMessages(roomId: RoomId): Flow<List<MessageEntry>> = flowOf(emptyList())
            override fun watchMessageIds(roomId: RoomId): Flow<List<MessageId>> = flowOf(emptyList())
        }
        val drafts = object : DraftsManager {
            override fun start(lockers: LockersClient) = Unit
            override fun stop() = Unit
            override suspend fun setText(roomId: RoomId, text: String) = Unit
            override suspend fun addAttachment(roomId: RoomId, attachment: DraftAttachment) = Unit
            override suspend fun removeAttachment(roomId: RoomId, contentId: ByteArray) = Unit
            override fun watchDraft(roomId: RoomId): Flow<Draft?> = flowOf(null)
            override suspend fun clear(roomId: RoomId) { cleared = true }
            override suspend fun clearIfUnchanged(roomId: RoomId, sent: Draft) { cleared = true }
        }
        assertFailsWith<CancellationException> {
            SendMessageUseCase(messages, drafts, session).send(RoomId(rawValue = byteArrayOf(1)), Draft(text = "same text"))
        }
        assertFalse(cleared)
    }
}
