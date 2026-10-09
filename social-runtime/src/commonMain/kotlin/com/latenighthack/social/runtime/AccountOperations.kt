package com.latenighthack.social.runtime

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class AccountOperation(val session: AccountSession, val owner: String, val generation: Long) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AccountOperation>
}

/** A public command and all of its children belong to the identity that admitted it. */
suspend fun <T> AccountSession.withAccount(block: suspend () -> T): T {
    requireOperationOwner()
    val operation = AccountOperation(this, currentOwner(), generation.value)
    return withContext(operation) {
        coroutineScope {
            val commands = this
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                combine(owner, generation) { id, epoch -> id != operation.owner || epoch != operation.generation }.first { it }
                commands.cancel("account changed during command")
            }
            try { requireOperationOwner(); block().also { requireOperationOwner() } }
            finally { watcher.cancel() }
        }
    }
}

/** Check after suspension and inside publication builders before using captured secrets. */
suspend fun AccountSession.requireOperationOwner() {
    currentCoroutineContext().ensureActive()
    val operation = currentCoroutineContext()[AccountOperation]
    if (operation != null && (operation.session !== this || owner.value != operation.owner || generation.value != operation.generation)) {
        throw kotlinx.coroutines.CancellationException("account changed during command")
    }
    check(owner.value != null) { "account is signed out" }
}
