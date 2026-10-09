package com.latenighthack.social.observability

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import me.tatarka.inject.annotations.Provides
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Clock
import kotlin.time.TimeSource

/** Host-owned sink. Libraries never initialize exporters or a global SDK. */
interface SocialTelemetry {
    val enabled: Boolean get() = true
    suspend fun begin(feature: String, operation: String, provider: String): SocialSpanScope = NoopSpanScope
    fun record(observation: SocialObservation)
    fun feature(feature: String) { record(SocialObservation(feature, "start", kind = "feature")) }
}

object NoopSocialTelemetry : SocialTelemetry {
    override val enabled = false
    override fun record(observation: SocialObservation) {}
}

interface SocialSpanScope {
    val context: CoroutineContext get() = EmptyCoroutineContext
    val ids: SocialTraceIds? get() = null
    fun finish(result: String) {}
}
object NoopSpanScope : SocialSpanScope

data class SocialTraceIds(val traceId: String, val spanId: String, val parentSpanId: String? = null)
data class SocialFeatureDescriptor(val name: String)

/** Inherited once by feature provider interfaces; override this method once in the host graph. */
interface SocialTelemetryProviders {
    @Provides fun socialTelemetry(): SocialTelemetry = NoopSocialTelemetry
}

/** Installed before start by the scoped provider, without replacing the identity-owning manager. */
interface SocialTelemetryOwner {
    var socialTelemetry: SocialTelemetry
}

fun <T : SocialTelemetryOwner> T.observedBy(telemetry: SocialTelemetry): T = apply {
    socialTelemetry = telemetry
}

class SocialOperation internal constructor() {
    var result: String = "ok"
        private set
    fun result(value: String) { result = if (value in SocialCatalogue.results) value else "unknown" }
}

/** Instrumentation failures are isolated; application exceptions and cancellation retain their identity. */
suspend fun <T> SocialTelemetry.measure(
    feature: String,
    operation: String,
    provider: String = "none",
    propagateContext: Boolean = true,
    block: suspend SocialOperation.() -> T,
): T {
    val state = SocialOperation()
    if (!enabled) return state.block()
    val span = try { begin(feature, operation, provider) } catch (_: Throwable) { NoopSpanScope }
    val start = Clock.System.now().let { it.epochSeconds * 1_000_000_000L + it.nanosecondsOfSecond }
    val elapsed = TimeSource.Monotonic.markNow()
    var originalFailure: Throwable? = null
    try {
        return if (!propagateContext || span.context == EmptyCoroutineContext) state.block()
        else withContext(span.context) {
            try { state.block() } catch (failure: Throwable) { originalFailure = failure; throw failure }
        }
    } catch (error: Throwable) {
        val failure = originalFailure ?: error
        if (failure is CancellationException) state.result("cancelled")
        else if (state.result == "ok" || state.result == "needs_binding") state.result("error")
        throw failure
    } finally {
        val duration = elapsed.elapsedNow().inWholeNanoseconds.coerceAtLeast(0)
        safely { record(SocialObservation(feature, operation, provider, state.result, duration,
            startTimeUnixNano = start, trace = span.ids)) }
        safely { span.finish(state.result) }
    }
}

fun SocialTelemetry.event(feature: String, operation: String, result: String = "ok", value: Double = 1.0, kind: String = "event", provider: String = "none") {
    if (enabled) safely { record(SocialObservation(feature, operation, provider = provider, result = result, kind = kind, value = value)) }
}

/** Deliberately never captures exception strings or user data. */
private inline fun safely(block: () -> Unit) { try { block() } catch (_: Throwable) {} }

/** One span per existing collection, with no eager subscription or extra collector. */
fun <T> kotlinx.coroutines.flow.Flow<T>.socialObserved(telemetry: SocialTelemetry, feature: String): kotlinx.coroutines.flow.Flow<T> =
    kotlinx.coroutines.flow.flow {
        telemetry.measure(feature, "watch", propagateContext = false) { collect { emit(it) } }
    }
