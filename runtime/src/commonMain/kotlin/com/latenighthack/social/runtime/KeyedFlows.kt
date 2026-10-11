package com.latenighthack.social.runtime

import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Retains observers and their last values across reorders; removed keys release their child jobs. */
fun <K, V> keyedFlows(keys: Flow<List<K>>, initial: (K) -> V, watch: (K) -> Flow<V>): Flow<List<V>> = channelFlow {
    val mutex = Mutex()
    var order = emptyList<K>()
    val values = mutableMapOf<K, V>()
    val jobs = mutableMapOf<K, Job>()
    keys.collect { next ->
        mutex.withLock {
            order = next.distinct()
            for (removed in jobs.keys.toList() - order.toSet()) {
                jobs.remove(removed)?.cancel()
                values.remove(removed)
            }
            for (key in order) if (key !in jobs) {
                values[key] = initial(key)
                jobs[key] = launch {
                    watch(key).collect { value ->
                        mutex.withLock {
                            if (key in order) {
                                values[key] = value
                                trySend(order.map { values.getValue(it) })
                            }
                        }
                    }
                }
            }
            trySend(order.map { values.getValue(it) })
        }
    }
}.buffer(1, BufferOverflow.DROP_OLDEST)
