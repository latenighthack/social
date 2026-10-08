package com.latenighthack.social.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ManagerRunnerTest {
    @Test fun restartWaitsForCleanupAndRejectsClientReplacement() = runTest {
        val runner = ManagerRunner(backgroundScope)
        val release = CompletableDeferred<Unit>()
        val token = Any()
        var generations = 0
        runner.start(token) {
            generations++
            try { awaitCancellation() } finally { withContext(NonCancellable) { release.await() } }
        }
        runCurrent()
        runner.start(token) { error("duplicate start") }
        assertFailsWith<IllegalStateException> { runner.start(Any()) { } }
        runner.stop()
        runner.start(token) { generations++; awaitCancellation() }
        runCurrent()
        assertEquals(1, generations)
        release.complete(Unit); runCurrent()
        assertEquals(2, generations)
        runner.stopAndJoin()
    }
    @Test fun stopAndJoinWaitsForAllChildren() = runTest {
        val runner = ManagerRunner(backgroundScope)
        val release = CompletableDeferred<Unit>()
        runner.start { launch { try { awaitCancellation() } finally { withContext(NonCancellable) { release.await() } } } }
        runCurrent()
        val stopped = launch { runner.stopAndJoin() }
        runCurrent(); assertFalse(stopped.isCompleted)
        release.complete(Unit); stopped.join()
    }
}
