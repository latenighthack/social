package com.latenighthack.social.observability.server

import com.latenighthack.social.observability.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.io.readByteArray
import kotlinx.serialization.json.*

/** Restricted reports only. Auth is provided by the host, including for pre-login operations. */
class SocialReportRelay(
    private val telemetry: SocialServerTelemetry,
    private val authorize: suspend (ApplicationCall) -> Boolean,
    private val tracesEndpoint: String?,
    private val logsEndpoint: String?,
    private val collectorHeaders: Map<String, String> = emptyMap(),
    private val client: HttpClient = HttpClient(CIO) { followRedirects = false },
) : AutoCloseable {
    init { telemetry.feature("runtime") }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<SocialReport>(256)
    private val permits = Semaphore(8)
    private val worker = scope.launch {
        try {
            for (report in queue) {
                for ((endpoint, body) in listOf(tracesEndpoint to traces(report), logsEndpoint to logs(report))) {
                    if (body == null) continue
                    if (endpoint == null) { telemetry.exporter("disabled"); continue }
                    val delivered = try {
                        withTimeout(5_000) {
                            client.post(endpoint) {
                                contentType(ContentType.Application.Json)
                                collectorHeaders.forEach { (key, value) -> header(key, value) }
                                setBody(body.toString())
                            }.status.value in 200..299
                        }
                    } catch (_: TimeoutCancellationException) { false }
                      catch (cancelled: CancellationException) { throw cancelled }
                      catch (_: Exception) { false }
                    telemetry.exporter(if (delivered) "ok" else "error")
                }
            }
        } finally { client.close() }
    }

    fun install(route: Route, path: String = SOCIAL_REPORT_PATH) {
        route.post(path) {
            if (!authorize(call)) { reject(call, HttpStatusCode.Unauthorized); return@post }
            if (!call.request.contentType().match(ContentType.Application.Json)) { reject(call, HttpStatusCode.UnsupportedMediaType); return@post }
            if (!permits.tryAcquire()) { reject(call, HttpStatusCode.TooManyRequests); return@post }
            try {
                if ((call.request.header(HttpHeaders.ContentLength)?.toLongOrNull() ?: 0L) > SOCIAL_REPORT_MAX_BYTES) {
                    reject(call, HttpStatusCode.PayloadTooLarge); return@post
                }
                val bytes = try { withTimeout(5_000) { call.receiveChannel().readRemaining(SOCIAL_REPORT_MAX_BYTES.toLong() + 1).readByteArray() } }
                    catch (_: TimeoutCancellationException) { reject(call, HttpStatusCode.RequestTimeout); return@post }
                if (bytes.size > SOCIAL_REPORT_MAX_BYTES) { reject(call, HttpStatusCode.PayloadTooLarge); return@post }
                val report = try { SocialReport.decode(bytes.decodeToString()) }
                    catch (_: Exception) { reject(call, HttpStatusCode.BadRequest); return@post }
                // Validate the entire report before recording anything. No upstream I/O precedes this acknowledgement.
                report.records.forEach { telemetry.ingest(it, "client", report.platform) }
                telemetry.dropped(report.platform, report.dropped)
                telemetry.exporter("accepted")
                if (!queue.trySend(report).isSuccess) telemetry.exporter("dropped", report.records.size.toDouble())
                call.respond(HttpStatusCode.Accepted)
            } finally { permits.release() }
        }
    }
    private suspend fun reject(call: ApplicationCall, status: HttpStatusCode) {
        telemetry.exporter("rejected"); call.respond(status)
    }
    override fun close() {
        queue.close()
        // Drain is bounded even if collectors are unreachable; host resources are never closed here.
        scope.launch { delay(5_000); worker.cancel(); scope.cancel() }
    }
    suspend fun awaitClosed() { withTimeoutOrNull(5_100) { worker.join() }; scope.cancel() }

    private fun resource(): JsonObject = buildJsonObject {
        putJsonArray("attributes") {
            mapOf("service.name" to "${telemetry.application}-client", "service.namespace" to telemetry.application,
                "deployment.environment" to telemetry.environment).forEach { (key, value) -> add(attribute(key, value)) }
        }
    }
    private fun attribute(key: String, value: String) = buildJsonObject {
        put("key", key); putJsonObject("value") { put("stringValue", value) }
    }
    private fun traces(report: SocialReport): JsonObject? {
        val records = report.records.filter { it.trace != null }
        if (records.isEmpty()) return null
        return buildJsonObject {
            putJsonArray("resourceSpans") { add(buildJsonObject {
                put("resource", resource())
                putJsonArray("scopeSpans") { add(buildJsonObject {
                    putJsonObject("scope") { put("name", "social.observability") }
                    putJsonArray("spans") { records.forEach { record -> add(buildJsonObject {
                        val ids = record.trace!!
                        put("traceId", ids.traceId); put("spanId", ids.spanId)
                        ids.parentSpanId?.let { put("parentSpanId", it) }
                        put("name", "social.${record.feature}.${record.operation}"); put("flags", 1)
                        put("kind", if (record.feature == "transport") 3 else 1)
                        put("startTimeUnixNano", record.startTimeUnixNano.toString())
                        put("endTimeUnixNano", (record.startTimeUnixNano + record.durationNanos).toString())
                        putJsonObject("status") { put("code", if (SocialCatalogue.outcome(record.result) == "error") 2 else 0) }
                        putJsonArray("attributes") {
                            mapOf("social.feature" to record.feature, "social.operation" to record.operation,
                                "social.result" to record.result, "social.provider" to record.provider,
                                "social.platform" to report.platform).forEach { (key, value) -> add(attribute(key, value)) }
                        }
                    }) } }
                }) }
            }) }
        }
    }
    private fun logs(report: SocialReport): JsonObject? {
        val records = report.records.filter { it.result != "ok" || it.operation in setOf("start", "stop", "prepare") }
        if (records.isEmpty()) return null
        return buildJsonObject {
            putJsonArray("resourceLogs") { add(buildJsonObject {
                put("resource", resource())
                putJsonArray("scopeLogs") { add(buildJsonObject {
                    putJsonObject("scope") { put("name", "social.observability") }
                    putJsonArray("logRecords") { records.forEach { record -> add(buildJsonObject {
                        put("timeUnixNano", (System.currentTimeMillis() * 1_000_000).toString())
                        put("severityNumber", if (SocialCatalogue.outcome(record.result) == "error") 17 else 9)
                        put("severityText", if (SocialCatalogue.outcome(record.result) == "error") "ERROR" else "INFO")
                        record.trace?.let { put("traceId", it.traceId); put("spanId", it.spanId) }
                        putJsonObject("body") { put("stringValue", telemetry.diagnostic(record, "client", report.platform).toString()) }
                    }) } }
                }) }
            }) }
        }
    }
}
