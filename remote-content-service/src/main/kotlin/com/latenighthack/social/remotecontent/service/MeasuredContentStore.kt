package com.latenighthack.social.remotecontent.service

import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

class MeasuredContentStore(private val store: ContentStore, private val meters: MeterRegistry, private val backend: String) : ContentStore, AutoCloseable {
    private suspend fun <T> measure(operation: String, block: suspend () -> T): T {
        val start = System.nanoTime(); var outcome = "success"
        try { return block() }
        catch (cancelled: CancellationException) { outcome = "cancelled"; throw cancelled }
        catch (error: Exception) { outcome = "error"; throw error }
        finally { meters.timer("fullhouse.content.duration", "operation", operation, "backend", backend, "outcome", outcome).record(System.nanoTime() - start, TimeUnit.NANOSECONDS) }
    }
    override suspend fun create(id: ByteArray, mimeType: String?) = measure("reserve") { store.create(id, mimeType) }
    override suspend fun put(id: ByteArray, bytes: ByteArray) = measure("upload") {
        store.put(id, bytes)
        meters.counter("fullhouse.content.bytes", "operation", "upload").increment(bytes.size.toDouble())
    }
    override suspend fun get(id: ByteArray): StoredContent? = measure("download") {
        store.get(id).also { value ->
            if (value == null) meters.counter("fullhouse.content.missing").increment()
            else meters.counter("fullhouse.content.bytes", "operation", "download").increment(value.bytes.size.toDouble())
        }
    }
    override fun close() { (store as? AutoCloseable)?.close() }
}
