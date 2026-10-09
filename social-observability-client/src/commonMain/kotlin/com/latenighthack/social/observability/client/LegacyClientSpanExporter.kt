package com.latenighthack.social.observability.client

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*

/** Bounded best-effort delivery, isolated from the application's instrumented HTTP clients. */
class LegacyClientSpanExporter(
    serverPath: String,
    private val headers: suspend () -> Map<String, String>,
    private val client: HttpClient = HttpClient { followRedirects = false },
    private val batchDelayMillis: Long = 1_000,
) {
    private val endpoint = (if (serverPath.startsWith("http")) serverPath else "http://$serverPath")
        .trimEnd('/') + "/api/telemetry/client-spans"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queue = Channel<ClientTimingSpan>(256)
    private val worker = scope.launch {
        try {
            for (first in queue) {
                delay(batchDelayMillis)
                val batch = mutableListOf(first)
                while (batch.size < 32) batch.add(queue.tryReceive().getOrNull() ?: break)
                val body = buildJsonObject { putJsonArray("spans") { batch.forEach { add(it.toJson()) } } }.toString()
                // Retry once; telemetry never holds up an application request or retries forever.
                if (!send(body)) { delay(250); send(body) }
            }
        } finally { client.close(); scope.cancel() }
    }

    private val enabled = serverPath.isNotBlank()
    fun offer(span: ClientTimingSpan) { if (enabled) queue.trySend(span) }
    fun close() { queue.close() }
    suspend fun awaitClosed() { worker.join() }

    private suspend fun send(body: String): Boolean = try {
        withTimeout(5_000) {
            val response = client.post(endpoint) {
                contentType(ContentType.Application.Json)
                headers().forEach { (key, value) -> header(key, value) }
                setBody(body)
            }
            response.status.value in 200..299
        }
    } catch (_: TimeoutCancellationException) {
        false
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
}

private fun ClientTimingSpan.toJson() = buildJsonObject {
    put("traceId", traceId)
    put("spanId", spanId)
    parentSpanId?.let { put("parentSpanId", it) }
    put("name", name)
    put("startTimeUnixNano", startTimeUnixNano.toString())
    put("endTimeUnixNano", endTimeUnixNano.toString())
    put("outcome", outcome)
}
