package com.latenighthack.social.observability.client

import com.latenighthack.ktbuf.net.*
import com.latenighthack.social.observability.socialHttpResult
import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.time.TimeSource

class ClientTraceContext private constructor(val traceId: String, val parentId: String, internal val recorded: Boolean) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ClientTraceContext> {
        suspend fun create(): ClientTraceContext = ClientTraceContext(randomId(16), randomId(8), false)
        internal suspend fun span(parent: ClientTraceContext?): ClientTraceContext =
            ClientTraceContext(parent?.traceId ?: randomId(16), randomId(8), true)

        private suspend fun randomId(size: Int): String {
            var bytes: ByteArray
            do { bytes = secureTraceBytes(size) } while (bytes.all { it == 0.toByte() })
            return bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        }
    }
    val traceparent: String get() = "00-$traceId-$parentId-01"
}

/** Groups requests under one trace ID without inventing an unexported parent span. */
suspend fun <T> withClientTrace(block: suspend (ClientTraceContext) -> T): T {
    val trace = currentCoroutineContext()[ClientTraceContext] ?: ClientTraceContext.create()
    return withContext(trace) { block(trace) }
}

/** Only transport metadata is exported. Times use epoch nanoseconds with monotonic durations. */
data class ClientTimingSpan(
    val traceId: String,
    val spanId: String,
    val parentSpanId: String?,
    val name: String,
    val startTimeUnixNano: Long,
    val endTimeUnixNano: Long,
    val outcome: String,
)

suspend fun <T> withClientTimingSpan(
    name: String,
    export: (ClientTimingSpan) -> Unit,
    isError: (T) -> Boolean = { false },
    block: suspend (ClientTraceContext) -> T,
): T {
    val parent = currentCoroutineContext()[ClientTraceContext]
    val trace = ClientTraceContext.span(parent)
    val now = Clock.System.now()
    val start = now.epochSeconds * 1_000_000_000L + now.nanosecondsOfSecond
    val elapsed = TimeSource.Monotonic.markNow()
    var outcome = "ok"
    return try {
        withContext(trace) { block(trace) }.also { if (isError(it)) outcome = "error" }
    } catch (cancelled: CancellationException) {
        outcome = "cancelled"
        throw cancelled
    } catch (error: Throwable) {
        outcome = "error"
        throw error
    } finally {
        // Export is a non-blocking queue offer, including when the request was cancelled.
        try { export(ClientTimingSpan(trace.traceId, trace.parentId, parent?.takeIf { it.recorded }?.parentId,
            name, start, start + elapsed.elapsedNow().inWholeNanoseconds.coerceAtLeast(0), outcome)) } catch (_: Exception) {}
    }
}

class TraceContextInterceptor(private val export: (ClientTimingSpan) -> Unit) : UnaryRpcInterceptor {
    override suspend fun intercept(method: RpcMethodSpecifier, headers: Map<String, String>, requestData: ByteArray, next: UnaryRpcCallback): RpcResponse =
        withClientTimingSpan("${method.packageName}.${method.serviceName}/${method.methodName}", export) { trace ->
            val outgoing = headers.filterKeys { !it.equals("traceparent", true) && !it.equals("tracestate", true) }
            next(method, outgoing + ("traceparent" to trace.traceparent), requestData)
        }
}

class TraceContextRpcClient(private val delegate: RpcClient, private val export: (ClientTimingSpan) -> Unit) : RpcClient {
    private val unary = InterceptingRpcClient(delegate, listOf(TraceContextInterceptor(export)))
    override suspend fun unaryCall(method: RpcMethodSpecifier, headers: Map<String, String>, request: ByteArray): RpcResponse =
        unary.unaryCall(method, headers, request)

    override suspend fun serverStreamingCall(method: RpcMethodSpecifier, block: suspend RpcServerStream.() -> Unit, readyCallback: () -> Unit) {
        withClientTimingSpan("STREAM ${method.packageName}.${method.serviceName}/${method.methodName}", export) { trace ->
            val query = method.additionalParameters - "tracestate" + ("traceparent" to trace.traceparent)
            delegate.serverStreamingCall(method.copy(additionalParameters = query), {
                val stream = this
                // ktbuf may run callbacks in its own coroutine scope.
                withContext(trace) { block(stream) }
            }, readyCallback)
        }
    }
}

/** Transport wrapper that keeps expected HTTP denials out of infrastructure error counts. */
suspend fun <T> withClientHttpSpan(
    name: String,
    export: (ClientTimingSpan) -> Unit,
    status: (T) -> Int,
    block: suspend (ClientTraceContext) -> T,
): T {
    var result: String? = null
    return withClientTimingSpan(name, { span ->
        export(span.copy(outcome = if (span.outcome == "cancelled") "cancelled" else result ?: span.outcome))
    }) { trace ->
        try { block(trace).also { result = socialHttpResult(status(it)) } }
        catch (response: ResponseException) { result = socialHttpResult(response.response.status.value); throw response }
    }
}
