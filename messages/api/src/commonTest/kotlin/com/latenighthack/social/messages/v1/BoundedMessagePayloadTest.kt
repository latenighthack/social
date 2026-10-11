package com.latenighthack.social.messages.v1

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class BoundedMessagePayloadTest {
    private fun field(tag: Int, bytes: ByteArray): ByteArray {
        fun varint(value: Int): ByteArray {
            var remaining = value
            val output = mutableListOf<Byte>()
            do {
                val byte = remaining and 127
                remaining = remaining ushr 7
                output += (byte or if (remaining == 0) 0 else 128).toByte()
            } while (remaining != 0)
            return output.toByteArray()
        }
        return varint((tag shl 3) or 2) + varint(bytes.size) + bytes
    }
    @Test fun maliciousDepthWidthLengthAndSizeAreRejectedBeforeRecursiveDecoding() {
        var component = ByteArray(0)
        repeat(2000) { component = field(2, field(1, component)) }
        assertNull(BoundedMessagePayload.decode(field(4, component)))
        val children = (0..512).fold(ByteArray(0)) { bytes, _ -> bytes + field(1, ByteArray(0)) }
        assertNull(BoundedMessagePayload.decode(field(4, field(2, children))))
        assertNull(BoundedMessagePayload.decode(byteArrayOf(34, 127)))
        assertNull(BoundedMessagePayload.decode(ByteArray(BoundedMessagePayload.MAX_BYTES + 1)))
        assertNotNull(BoundedMessagePayload.decode(MessagePayload { this.component = Component { contents.text { text = "hello" } } }.toByteArray()))
    }
}
