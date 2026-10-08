package com.latenighthack.social.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class KeyedFlowsTest {
    @Test fun reorderingRetainsValuesAndSubscriptionsAndRemovalCancelsThem() = runTest {
        val ids = MutableStateFlow(listOf(1, 2))
        val runs = mutableMapOf<Int, Int>()
        val cancelled = mutableListOf<Int>()
        var result = emptyList<String>()
        val job = backgroundScope.launch {
            keyedFlows(ids, { "loading $it" }) { id -> flow {
                runs[id] = (runs[id] ?: 0) + 1
                try { emit("loaded $id"); kotlinx.coroutines.awaitCancellation() }
                finally { cancelled += id }
            } }.collect { result = it }
        }
        runCurrent(); assertEquals(listOf("loaded 1", "loaded 2"), result)
        ids.value = listOf(2, 1); runCurrent()
        assertEquals(listOf("loaded 2", "loaded 1"), result)
        assertEquals(mapOf(1 to 1, 2 to 1), runs)
        ids.value = listOf(2); runCurrent(); assertEquals(listOf(1), cancelled)
        job.cancel(); job.join()
    }
}
