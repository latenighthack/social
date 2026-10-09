package com.latenighthack.social.messages.domain

import com.latenighthack.social.messages.v1.MessagePayload
import com.latenighthack.social.messages.v1.MessageDeliveryStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class MessageOrderingTest {
    @Test fun clockSkewAndArrivalOrderDoNotChangeMessageOrder() {
        fun entry(id: Int, counter: Long, time: Long) = MessageEntry(
            MessagePayload(messageId = byteArrayOf(id.toByte()), orderingCounter = counter, sentAtMillis = time),
            MessageDeliveryStatus.MESSAGE_DELIVERY_STATUS_SENT)
        val first = entry(255, 1, Long.MAX_VALUE)
        val concurrent = entry(1, 2, 0)
        val second = entry(2, 2, -999)
        assertEquals(listOf(first, concurrent, second), listOf(second, first, concurrent).sortedWith(messageOrder))
        assertEquals(listOf(first, concurrent, second), listOf(concurrent, second, first).sortedWith(messageOrder))
    }
}
