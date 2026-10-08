package com.latenighthack.social.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
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
        assertFailsWith<IllegalStateException> {
            session.withAccount {
                session.generation.value++
                session.requireOperationOwner()
            }
        }
    }
}
