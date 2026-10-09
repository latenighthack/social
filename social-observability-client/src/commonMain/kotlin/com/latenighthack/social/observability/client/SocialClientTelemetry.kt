package com.latenighthack.social.observability.client

import com.latenighthack.social.observability.*
import com.latenighthack.ktbuf.net.RpcClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.util.AttributeKey
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.coroutines.coroutineContext

/** Memory-only telemetry. The supplied client must be separate from the instrumented application client. */
class SocialClientTelemetry(
    serverUrl: String,
    private val platform: String,
    private val headers: suspend () -> Map<String, String>,
    private val client: HttpClient = HttpClient { followRedirects = false },
    private val path: String = SOCIAL_REPORT_PATH,
    private val flushMillis: Long = 1_000,
    capacity: Int = 256,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : SocialTelemetry {
    init { require(platform in SocialCatalogue.platforms); require(flushMillis > 0); require(capacity > 0) }
    override val enabled = serverUrl.isNotBlank()
    private val endpoint = (if (serverUrl.startsWith("http")) serverUrl else "http://$serverUrl").trimEnd('/') + path
    private val queue = Channel<SocialObservation>(capacity)
    private val lost = MutableStateFlow(0L)
    private val failedExports = MutableStateFlow(0L)
    private val rejectedExports = MutableStateFlow(0L)
    private val features = MutableStateFlow<Set<String>>(setOf("runtime"))
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val worker = scope.launch {
        try {
            for (first in queue) {
                delay(flushMillis)
                val failures = failedExports.value.coerceAtMost(1_000_000)
                val rejections = rejectedExports.value.coerceAtMost(1_000_000)
                val reserved = (if (failures > 0) 1 else 0) + (if (rejections > 0) 1 else 0)
                val batch = mutableListOf(first)
                while (batch.size < SOCIAL_REPORT_MAX_RECORDS - reserved) batch.add(queue.tryReceive().getOrNull() ?: break)
                if (failures > 0) batch += SocialObservation("runtime", "export", result = "error", kind = "event", value = failures.toDouble())
                if (rejections > 0) batch += SocialObservation("runtime", "export", result = "rejected", kind = "event", value = rejections.toDouble())
                val dropped = lost.value.coerceAtMost(1_000_000)
                val body = SocialReport(platform, batch, dropped).toJson().toString()
                if (body.encodeToByteArray().size > SOCIAL_REPORT_MAX_BYTES) {
                    lost.update { it + batch.size }; continue
                }
                val status = send(body)
                val accepted = if (status == 429 || status == 503) {
                    delay(250); send(body) in 200..299
                } else status in 200..299
                if (accepted) {
                    lost.update { (it - dropped).coerceAtLeast(0) }
                    failedExports.update { (it - failures).coerceAtLeast(0) }
                    rejectedExports.update { (it - rejections).coerceAtLeast(0) }
                } else lost.update { it + batch.size }
            }
        } finally { client.close(); scope.cancel() }
    }
    private val heartbeat = scope.launch {
        while (isActive) {
            delay(30_000)
            features.value.forEach { record(SocialObservation(it, "start", kind = "feature")) }
        }
    }

    override fun feature(feature: String) {
        require(feature in SocialCatalogue.operations)
        features.update { it + feature }
        record(SocialObservation(feature, "start", kind = "feature"))
    }

    override suspend fun begin(feature: String, operation: String, provider: String): SocialSpanScope {
        val parent = coroutineContext[ClientTraceContext]
        val trace = ClientTraceContext.span(parent)
        return object : SocialSpanScope {
            override val context = trace
            override val ids = SocialTraceIds(trace.traceId, trace.parentId, parent?.takeIf { it.recorded }?.parentId)
        }
    }

    override fun record(observation: SocialObservation) {
        if (!enabled) return
        observation.validate()
        if (!queue.trySend(observation).isSuccess) lost.update { it + 1 }
    }

    /** Compatibility bridge for host transport instrumentation; no URLs, methods or payloads are retained. */
    fun offer(span: ClientTimingSpan) = record(SocialObservation("transport",
        when { span.name.startsWith("HTTP ") -> "http"; span.name.startsWith("STREAM ") -> "stream"; else -> "rpc" },
        result = span.outcome.takeIf { it in SocialCatalogue.results } ?: "error",
        durationNanos = (span.endTimeUnixNano - span.startTimeUnixNano).coerceAtLeast(0),
        startTimeUnixNano = span.startTimeUnixNano,
        trace = SocialTraceIds(span.traceId, span.spanId, span.parentSpanId)))

    /** Configure the host's shared transport once, regardless of its selected feature providers. */
    fun instrumentRpcClient(client: RpcClient): RpcClient =
        if (enabled) TraceContextRpcClient(client, ::offer) else client

    /** Keeps the host's HTTP client identity and ownership; never instruments the exporter client. */
    fun instrumentHttpClient(http: HttpClient): HttpClient {
        require(http !== client) { "Telemetry exports require a separate, uninstrumented HTTP client" }
        if (!enabled) return http
        val existing = http.attributes.getOrNull(HTTP_TELEMETRY)
        if (existing != null) { require(existing === this); return http }
        http.attributes.put(HTTP_TELEMETRY, this)
        http.plugin(HttpSend).intercept { request ->
            withClientHttpSpan("HTTP ${request.method.value}", ::offer, { it.response.status.value }) { trace ->
                request.headers.remove("tracestate")
                request.headers["traceparent"] = trace.traceparent
                execute(request)
            }
        }
        return http
    }

    fun close() {
        heartbeat.cancel(); queue.close()
        scope.launch { delay(5_000); worker.cancel() }
    }
    suspend fun awaitClosed(timeoutMillis: Long = 5_000) {
        try { withTimeout(timeoutMillis) { worker.join() } }
        finally { scope.cancel() }
    }

    private companion object { val HTTP_TELEMETRY = AttributeKey<SocialClientTelemetry>("social.http.telemetry") }

    private suspend fun send(body: String): Int = (try {
        withTimeout(5_000) {
            client.post(endpoint) {
                contentType(ContentType.Application.Json)
                headers().forEach { (key, value) -> header(key, value) }
                setBody(body)
            }.status.value
        }
    } catch (_: TimeoutCancellationException) { 0 }
      catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { 0 }).also { status ->
          if (status !in 200..299) {
              if (status in 400..499 || status == 503) rejectedExports.update { it + 1 }
              else failedExports.update { it + 1 }
          }
      }
}
