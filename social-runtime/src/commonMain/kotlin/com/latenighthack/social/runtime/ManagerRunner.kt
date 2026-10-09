package com.latenighthack.social.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Serializes generations, including asynchronous cancellation cleanup. The host owns [scope]. */
class ManagerRunner(private val scope: CoroutineScope) {
    private data class Generation(val token: Any?, val job: Job, val ready: CompletableDeferred<Unit>)
    val token: Any? get() = generation.value?.takeIf { it.job.isActive }?.token
    private val generation = MutableStateFlow<Generation?>(null)

    fun start(token: Any? = null, block: suspend CoroutineScope.() -> Unit) {
        scope.coroutineContext.ensureActive()
        while (true) {
            val previous = generation.value
            if (previous != null && !previous.job.isCompleted && !previous.job.isCancelled) {
                check(previous.token === token) { "stopAndJoin before replacing the active client" }
                return
            }
            val ready = CompletableDeferred<Unit>()
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    previous?.job?.join()
                    ready.complete(Unit)
                    block()
                    awaitCancellation()
                } finally { ready.cancel() }
            }
            job.invokeOnCompletion { ready.cancel() }
            if (generation.compareAndSet(previous, Generation(token, job, ready))) {
                job.start()
                return
            }
            job.cancel()
        }
    }

    /** Public network commands are children of the admitted generation and their caller. */
    suspend fun <T> command(block: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        val current = generation.value?.takeIf { it.job.isActive } ?: run {
            currentCoroutineContext().ensureActive()
            error("manager must be started")
        }
        current.ready.await()
        check(generation.value === current) { "manager generation changed" }
        val parent = SupervisorJob(current.job)
        val operation = CoroutineScope(currentCoroutineContext().minusKey(Job) + parent).async { block() }
        try { return operation.await() }
        finally { withContext(NonCancellable) { parent.cancelAndJoin() } }
    }

    fun stop() { generation.value?.job?.cancel() }

    suspend fun stopAndJoin() {
        val previous = generation.value ?: return
        previous.job.cancelAndJoin()
        generation.compareAndSet(previous, null)
    }
}
