package com.latenighthack.social.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

sealed interface TaskHealth {
    data object Idle : TaskHealth
    data object Running : TaskHealth
    data class Recovering(val attempt: Int, val reason: String) : TaskHealth
}

/** Restart the entire child tree on transient failure; cancellation always belongs to the host. */
suspend fun recoverTask(health: MutableStateFlow<TaskHealth>, block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
    var attempt = 0
    try {
        while (true) {
            health.value = TaskHealth.Running
            try {
                coroutineScope { block() }
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                attempt = (attempt + 1).coerceAtMost(16)
                health.value = TaskHealth.Recovering(attempt, failure.message ?: failure::class.simpleName.orEmpty())
                delay((250L shl (attempt - 1)).coerceAtMost(30_000L))
            }
        }
    } finally {
        health.value = TaskHealth.Idle
    }
}
