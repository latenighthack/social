package com.latenighthack.social.messages.domain

import com.latenighthack.ktstore.Database
import com.latenighthack.ktstore.InMemoryStoreDelegate
import com.latenighthack.lockers.common.v1.RoomId
import com.latenighthack.social.common.v1.SignedContent
import com.latenighthack.social.messages.v1.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MessageHistoryPagingTest {
    @Test fun liveWindowIsBoundedAndEarlierDurableHistoryRemainsAvailable() = runTest {
        val database = Database(MessagesStorage.configuration("paged-history"), InMemoryStoreDelegate())
        database.open()
        val store = MessageStore(database)
        store.prepare()
        val room = RoomId(rawValue = byteArrayOf(1))
        repeat(1205) { number ->
            val id = ByteArray(32).also { it[0] = (number ushr 8).toByte(); it[1] = number.toByte() }
            val payload = MessagePayload(messageId = id, orderingCounter = number.toLong(), roomId = room.rawValue)
            store.saveMessage(LocalMessage(roomId = room.rawValue, messageId = MessageId(rawValue = id),
                message = SignedContent(content = payload.toByteArray()), ownerAccountId = "alice"))
        }
        val recent = store.getRecentMessages(room, { it.ownerAccountId == "alice" }, 10)
        assertEquals((1195L..1204L).toList(), recent.map { MessagePayload.fromByteArray(it.message!!.content).orderingCounter })
        val before = MessageEntry(MessagePayload.fromByteArray(recent.first().message!!.content),
            MessageDeliveryStatus.MESSAGE_DELIVERY_STATUS_SENT)
        val earlier = store.getRecentMessages(room, { true }, 10, before)
        assertEquals((1185L..1194L).toList(), earlier.map { MessagePayload.fromByteArray(it.message!!.content).orderingCounter })
        assertEquals(1205, store.getMessagesForRoom(room).size)
    }
}
