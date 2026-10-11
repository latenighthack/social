package com.latenighthack.social.observability.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import kotlinx.io.readByteArray
import kotlinx.serialization.json.*

/** Restricted client-span ingestion, not a general-purpose OTLP proxy. */
class LegacyClientSpanRelay(
    private val endpoint: String?,
    private val collectorHeaders: Map<String, String> = emptyMap(),
    private val client: HttpClient = HttpClient(CIO) { followRedirects = false },
) : AutoCloseable {
    private val permits = Semaphore(8)

    fun install(route: Route) {
        route.post("/api/telemetry/client-spans") {
            if (endpoint == null) { call.respond(HttpStatusCode.ServiceUnavailable); return@post }
            if (!call.request.contentType().match(ContentType.Application.Json)) {
                call.respond(HttpStatusCode.UnsupportedMediaType); return@post
            }
            if (!permits.tryAcquire()) { call.respond(HttpStatusCode.TooManyRequests); return@post }
            try {
                if ((call.request.header(HttpHeaders.ContentLength)?.toLongOrNull() ?: 0) > MAX_BYTES) {
                    call.respond(HttpStatusCode.PayloadTooLarge); return@post
                }
                val bytes = try {
                    withTimeout(5_000) { call.receiveChannel().readRemaining(MAX_BYTES + 1).readByteArray() }
                } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                    call.respond(HttpStatusCode.RequestTimeout); return@post
                }
                if (bytes.size > MAX_BYTES) { call.respond(HttpStatusCode.PayloadTooLarge); return@post }
                val payload = try {
                    clientSpansToOtlp(Json.parseToJsonElement(bytes.decodeToString()).jsonObject).toString()
                } catch (_: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest); return@post
                }
                val delivered = try {
                    withTimeout(5_000) {
                        val response = client.post(endpoint) {
                            contentType(ContentType.Application.Json)
                            collectorHeaders.forEach { (key, value) -> header(key, value) }
                            setBody(payload)
                        }
                        response.status.value in 200..299
                    }
                } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                    false
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                call.respond(if (delivered) HttpStatusCode.Accepted else HttpStatusCode.ServiceUnavailable)
            } finally { permits.release() }
        }
    }

    override fun close() { client.close() }

    companion object {
        private const val MAX_BYTES = 65_536L
        fun fromEnv(env: Map<String, String> = System.getenv()): LegacyClientSpanRelay {
            val protocol = env["OTEL_EXPORTER_OTLP_TRACES_PROTOCOL"] ?: env["OTEL_EXPORTER_OTLP_PROTOCOL"]
            val endpoint = if (env["OTEL_SDK_DISABLED"] == "true") null else env["CLIENT_TRACES_OTLP_HTTP_ENDPOINT"]
                ?: if (protocol == "grpc") null else env["OTEL_EXPORTER_OTLP_TRACES_ENDPOINT"]
                    ?: (env["OTEL_EXPORTER_OTLP_ENDPOINT"] ?: "http://localhost:4318").trimEnd('/') + "/v1/traces"
            val headers = (env["OTEL_EXPORTER_OTLP_TRACES_HEADERS"] ?: env["OTEL_EXPORTER_OTLP_HEADERS"])
                .orEmpty().split(',').filter { it.contains('=') }.associate {
                    val pair = it.split('=', limit = 2)
                    pair[0].trim() to java.net.URLDecoder.decode(pair[1].trim(), Charsets.UTF_8)
                }
            return LegacyClientSpanRelay(endpoint, headers)
        }
    }
}

/** Rebuild the OTLP envelope so clients cannot impersonate backend services or attach payloads. */
fun clientSpansToOtlp(input: JsonObject): JsonObject {
    require(input.keys == setOf("spans"))
    val spans = input.getValue("spans").jsonArray
    require(spans.size in 1..32)
    val normalized = spans.map { item ->
        val span = item.jsonObject
        require(span.keys.all { it in setOf("traceId", "spanId", "parentSpanId", "name", "startTimeUnixNano", "endTimeUnixNano", "outcome") })
        fun field(key: String) = span[key]?.jsonPrimitive?.content ?: throw IllegalArgumentException("missing field")
        fun id(key: String, size: Int): String = field(key).also {
            require(it.matches(Regex("[0-9a-f]{$size}")) && it.any { digit -> digit != '0' })
        }
        val traceId = id("traceId", 32)
        val spanId = id("spanId", 16)
        val parent = if ("parentSpanId" in span) id("parentSpanId", 16) else null
        val name = field("name").also { require(it.matches(Regex("[A-Za-z0-9_. /-]{1,200}"))) }
        val start = field("startTimeUnixNano").toLong()
        val end = field("endTimeUnixNano").toLong()
        require(start > 0 && end >= start)
        val outcome = field("outcome").also { require(it in setOf("ok", "error", "cancelled")) }
        buildJsonObject {
            put("traceId", traceId); put("spanId", spanId)
            parent?.let { put("parentSpanId", it) }
            put("name", name); put("flags", 1); put("kind", 3) // OTLP SPAN_KIND_CLIENT
            put("startTimeUnixNano", start.toString()); put("endTimeUnixNano", end.toString())
            putJsonObject("status") { put("code", if (outcome == "error") 2 else 0) }
            putJsonArray("attributes") {
                add(buildJsonObject { put("key", "client.request.outcome"); putJsonObject("value") { put("stringValue", outcome) } })
            }
        }
    }
    return buildJsonObject {
        putJsonArray("resourceSpans") {
            add(buildJsonObject {
                putJsonObject("resource") {
                    putJsonArray("attributes") {
                        for ((key, value) in mapOf("service.name" to "fullhouse-client", "service.namespace" to "fullhouse")) {
                            add(buildJsonObject { put("key", key); putJsonObject("value") { put("stringValue", value) } })
                        }
                    }
                }
                putJsonArray("scopeSpans") {
                    add(buildJsonObject {
                        putJsonObject("scope") { put("name", "fullhouse.client") }
                        put("spans", JsonArray(normalized))
                    })
                }
            })
        }
    }
}
