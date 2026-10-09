package com.latenighthack.social.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AccountOperationsTest {
    private class Session : AccountSession {
        override val owner = MutableStateFlow<String?>("alice")
        override val generation = MutableStateFlow(0L)
    }
    @Test fun identityChangeCancelsTheOwnedCommandAndItsChildren() = runTest {
        val session = Session()
        val entered = CompletableDeferred<Unit>()
        var cleaned = false
        val command = async {
            session.withAccount {
                entered.complete(Unit)
                try { awaitCancellation() } finally { cleaned = true }
            }
        }
        entered.await()
        session.owner.value = "bob"
        assertFailsWith<CancellationException> { command.await() }
        assertTrue(cleaned)
    }
    @Test fun signOutAndRestoreOfTheSameAccountStillWithdrawsInFlightAuthority() = runTest {
        val session = Session()
        assertFailsWith<CancellationException> {
            session.withAccount {
                session.generation.value++
                session.requireOperationOwner()
            }
        }
    }
    @Test fun ownerCollectorsRestartWhenTheSameIdentityIsRestored() = runTest {
        val session = Session()
        val seen = mutableListOf<String?>()
        val collector = backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
            session.ownerChanges().collect { seen.add(it) }
        }
        runCurrent()
        session.generation.value++
        runCurrent()
        kotlin.test.assertEquals(listOf<String?>("alice", "alice"), seen)
        collector.cancel()
    }
    @Test fun nestedCommandsCannotReplaceAWithdrawnAccountContext() = runTest {
        val session = Session()
        var nested = false
        assertFailsWith<CancellationException> {
            session.withAccount {
                session.owner.value = "bob"
                session.withAccount { nested = true }
            }
        }
        kotlin.test.assertFalse(nested)
    }
}
