package com.latenighthack.social.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Runnable
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
    @Test fun stoppingBeforeDispatchReleasesCommandsWaitingForReadiness() = runTest {
        val queued = ArrayDeque<Runnable>()
        val dispatcher = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { queued.add(block) }
        }
        val parent = kotlinx.coroutines.SupervisorJob()
        val runner = ManagerRunner(kotlinx.coroutines.CoroutineScope(parent + dispatcher))
        try {
            runner.start { awaitCancellation() }
            val command = launch { runner.command { error("stopped command must not execute") } }
            runCurrent()
            runner.stop()
            while (queued.isNotEmpty()) queued.removeFirst().run()
            runCurrent()
            kotlin.test.assertTrue(command.isCompleted)
        } finally { parent.cancel() }
    }

    @Test fun commandsWaitForPriorCleanupAndStopJoinsTheirChildren() = runTest {
        val runner = ManagerRunner(backgroundScope)
        val release = CompletableDeferred<Unit>()
        val old = Any()
        val replacement = Any()
        runner.start(old) {
            try { awaitCancellation() } finally { withContext(NonCancellable) { release.await() } }
        }
        runCurrent()
        runner.stop()
        runner.start(replacement) { awaitCancellation() }
        assertEquals(replacement, runner.token)
        var admitted = false
        var cleaned = false
        val command = launch {
            runner.command {
                admitted = true
                try { awaitCancellation() } finally { cleaned = true }
            }
        }
        runCurrent(); assertFalse(admitted)
        release.complete(Unit); runCurrent()
        kotlin.test.assertTrue(admitted)
        runner.stopAndJoin()
        command.join()
        kotlin.test.assertTrue(cleaned)
        kotlin.test.assertNull(runner.token)
    }

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
