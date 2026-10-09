package com.latenighthack.social.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TaskRecoveryTest {
    @Test fun failureIsVisibleAndRetriedWithBackoff() = runTest {
        val health = MutableStateFlow<TaskHealth>(TaskHealth.Idle)
        var runs = 0
        val job = launch { recoverTask(health) { if (++runs < 3) error("https://upload.example/private?token=secret-capability") else awaitCancellation() } }
        runCurrent()
        assertEquals("IllegalStateException", assertIs<TaskHealth.Recovering>(health.value).reason)
        advanceTimeBy(249); runCurrent(); assertEquals(1, runs)
        advanceTimeBy(1); runCurrent(); assertEquals(2, runs)
        advanceTimeBy(500); runCurrent(); assertEquals(3, runs)
        assertEquals(TaskHealth.Running, health.value)
        job.cancel(); job.join()
        assertEquals(TaskHealth.Idle, health.value)
    }
    @Test fun cancellationIsNeverRetried() = runTest {
        val health = MutableStateFlow<TaskHealth>(TaskHealth.Idle)
        var runs = 0
        val job = launch { recoverTask(health) { runs++; throw CancellationException("host stopped") } }
        job.join()
        assertEquals(1, runs)
        assertEquals(TaskHealth.Idle, health.value)
    }
}
