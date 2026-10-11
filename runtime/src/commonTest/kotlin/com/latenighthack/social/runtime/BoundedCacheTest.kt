package com.latenighthack.social.runtime

import kotlin.test.Test
import kotlin.test.assertEquals

class BoundedCacheTest {
    @Test fun authenticatedUnknownMembersCannotAccumulateUnlimitedOrExpiredMessages() {
        var now = 0L
        val cache = BoundedCache<Int, String>(3, 100) { now }
        repeat(1000) { cache.put(it, "message $it") }
        assertEquals(listOf("message 997", "message 998", "message 999"), cache.values())
        now = 99; assertEquals(3, cache.values().size)
        now = 100; assertEquals(emptyList(), cache.values())
    }
}
