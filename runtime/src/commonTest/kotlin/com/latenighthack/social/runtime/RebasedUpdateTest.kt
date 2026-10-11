package com.latenighthack.social.runtime

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class RebasedUpdateTest {
    @Test fun signaturePreparationUsesTheFreshCasValue() = runTest {
        data class Value(val name: String, val avatar: String, val signature: String)
        val stale = Value("old", "old", "old")
        var stored = Value("peer name", "old", "peer signature")
        val preparedFrom = mutableListOf<String>()
        val result = rebasedUpdate(stale, prepare = { base ->
            preparedFrom += base.name
            base.copy(avatar = "my avatar", signature = "${base.name}/my avatar")
        }, commit = { transform -> transform(stored).also { stored = it } })
        assertEquals(listOf("old", "peer name"), preparedFrom)
        assertEquals(Value("peer name", "my avatar", "peer name/my avatar"), result)
    }
}
