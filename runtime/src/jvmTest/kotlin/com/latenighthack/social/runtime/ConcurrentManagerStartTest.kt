package com.latenighthack.social.runtime

import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class ConcurrentManagerStartTest {
    @Test fun simultaneousStartsReserveExactlyOneGeneration() = runBlocking {
        val executor = Executors.newFixedThreadPool(16)
        try {
            repeat(200) {
                val parent = SupervisorJob()
                val runner = ManagerRunner(CoroutineScope(parent + Dispatchers.Default))
                val barrier = CyclicBarrier(16)
                val token = Any()
                try {
                    val starts = (1..16).map { executor.submit { barrier.await(); runner.start(token) { awaitCancellation() } } }
                    starts.forEach { it.get() }
                    assertEquals(1, parent.children.count(), "simultaneous starts leaked a prior generation")
                } finally { parent.cancelAndJoin() }
            }
        } finally { executor.shutdownNow() }
    }
}
