package com.latenighthack.social.messages.v1

import kotlin.test.Test
import kotlin.test.assertEquals

class InlineRangesTest {
    @Test fun utf16RangesHandleEmojiCombiningMarksAndHostileEndpoints() {
        val text = "😀 café é secret"
        assertEquals(3 to 7, inlineRange(text, 3, 4))
        assertEquals(8 to 10, inlineRange(text, 8, 2))
        assertEquals(0 to 2, inlineRange(text, 1, 1))
        assertEquals(1 to 1, inlineRange(text, 1, 0))
        assertEquals(0 to text.length, inlineRange(text, 1, Int.MAX_VALUE))
        assertEquals(0 to 2, inlineRange(text, -2, 3))
    }
}
