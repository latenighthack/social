package com.latenighthack.social.messages.domain

import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.social.common.v1.SignedContent
import com.latenighthack.social.messages.v1.LocalMessage
import com.latenighthack.social.messages.v1.MessageId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class CorruptHistoryRegressionTest {
    @Test fun malformedCachedPayloadDoesNotCrashHistoryHydration() = runBlocking {
        val database = MessagesStorage.inMemory("corrupt-history")
        database.open()
        try {
            val room = RoomId(rawValue = byteArrayOf(1))
            val store = MessageStore(database)
            store.saveMessage(LocalMessage(roomId = room.rawValue,
                messageId = MessageId(rawValue = byteArrayOf(2)),
                message = SignedContent(content = byteArrayOf(34, 127))))
            assertTrue(store.getRecentMessages(room, { true }).isEmpty())
        } finally { database.close() }
    }
}
