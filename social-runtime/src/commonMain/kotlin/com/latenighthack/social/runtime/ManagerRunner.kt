package com.latenighthack.social.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Serializes generations, including asynchronous cancellation cleanup. The host owns [scope]. */
class ManagerRunner(private val scope: CoroutineScope) {
    private data class Generation(val token: Any?, val job: Job)
    private val generation = MutableStateFlow<Generation?>(null)

    fun start(token: Any? = null, onStart: () -> Unit = {}, block: suspend CoroutineScope.() -> Unit) {
        while (true) {
            val previous = generation.value
            if (previous?.job?.isActive == true) {
                check(previous.token === token) { "stopAndJoin before replacing the active client" }
                return
            }
            val job = scope.launch(start = CoroutineStart.LAZY) {
                previous?.job?.join()
                block()
            }
            if (generation.compareAndSet(previous, Generation(token, job))) {
                onStart()
                job.start()
                return
            }
            job.cancel()
        }
    }

    fun stop() { generation.value?.job?.cancel() }

    suspend fun stopAndJoin() {
        val previous = generation.value ?: return
        previous.job.cancelAndJoin()
        generation.compareAndSet(previous, null)
    }
}
