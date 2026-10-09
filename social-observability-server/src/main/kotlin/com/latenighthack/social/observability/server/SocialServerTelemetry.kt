package com.latenighthack.social.observability.server

import com.latenighthack.social.observability.*
import io.micrometer.core.instrument.*
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context
import io.opentelemetry.extension.kotlin.asContextElement
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** All meters belong to the parent's registry. Closing this adapter never closes that registry/SDK. */
class SocialServerTelemetry(
    val registry: MeterRegistry,
    val application: String = System.getenv("SOCIAL_OBSERVABILITY_APPLICATION") ?: "social",
    val environment: String = System.getenv("DEPLOYMENT_ENV") ?: "local",
    private val openTelemetry: OpenTelemetry = GlobalOpenTelemetry.get(),
) : SocialTelemetry {
    private val log = LoggerFactory.getLogger("social.observability")
    private val enabledFeatures = ConcurrentHashMap<String, AtomicInteger>()
    private val lastReports = ConcurrentHashMap<String, AtomicLong>()
    private fun tags(side: String, platform: String) = listOf(Tag.of("application", application), Tag.of("environment", environment),
        Tag.of("side", side), Tag.of("platform", platform))

    override fun feature(feature: String) {
        require(feature in SocialCatalogue.operations)
        val value = enabledFeatures.computeIfAbsent(feature) {
            AtomicInteger(1).also { Gauge.builder("social.feature.enabled", it) { n -> n.get().toDouble() }
                .tags(tags("server", "jvm") + Tag.of("feature", feature)).register(registry) }
        }
        value.set(1)
    }
    fun provider(provider: String, enabled: Boolean) {
        require(provider in SocialCatalogue.providers)
        registry.gauge("social.login.provider.enabled", tags("server", "jvm") + Tag.of("provider", provider),
            enabledFeatures.computeIfAbsent("provider:$provider") { AtomicInteger() })?.set(if (enabled) 1 else 0)
    }

    override suspend fun begin(feature: String, operation: String, provider: String): SocialSpanScope {
        val parent = Context.current()
        val span = openTelemetry.getTracer("social.observability").spanBuilder("social.$feature.$operation")
            .setParent(parent).setSpanKind(SpanKind.INTERNAL)
            .setAttribute("social.feature", feature).setAttribute("social.operation", operation)
            .setAttribute("social.provider", provider).startSpan()
        val ids = span.spanContext.takeIf { it.isValid }?.let { SocialTraceIds(it.traceId, it.spanId) }
        return object : SocialSpanScope {
            override val context = parent.with(span).asContextElement()
            override val ids = ids
            override fun finish(result: String) {
                span.setAttribute("social.result", result)
                if (SocialCatalogue.outcome(result) == "error") span.setStatus(StatusCode.ERROR)
                span.end()
            }
        }
    }
    override fun record(observation: SocialObservation) {
        ingest(observation, "server", "jvm")
        if (observation.result != "ok" || observation.operation in setOf("prepare", "start", "stop")) {
            log.info(diagnostic(observation, "server", "jvm").toString())
        }
    }

    fun ingest(record: SocialObservation, side: String, platform: String) {
        record.validate()
        require(side in setOf("client", "server") && platform in SocialCatalogue.platforms)
        val common = tags(side, platform) + Tag.of("feature", record.feature)
        if (side == "client") {
            lastReports.computeIfAbsent("${record.feature}:$platform") {
                AtomicLong().also { value -> Gauge.builder("social.client.last.report.timestamp.seconds", value) { n -> n.get() / 1000.0 }
                    .tags(common).register(registry) }
            }.set(System.currentTimeMillis())
        }
        if (side == "client" && record.feature == "runtime" && record.operation == "export" && record.kind == "event") {
            registry.counter("social.telemetry.exports", tags(side, platform) + Tag.of("result", record.result)).increment(record.value)
        }
        val dimensions = common + listOf(Tag.of("operation", record.operation), Tag.of("provider", record.provider),
            Tag.of("result", record.result), Tag.of("outcome", SocialCatalogue.outcome(record.result)))
        when (record.kind) {
            "feature" -> registry.counter("social.client.feature.reports", common).increment()
            "operation" -> {
                registry.counter("social.operations", dimensions).increment()
                Timer.builder("social.operation.duration").tags(dimensions)
                    .serviceLevelObjectives(*LATENCY_BUCKETS).register(registry)
                    .record(record.durationNanos, java.util.concurrent.TimeUnit.NANOSECONDS)
            }
            "event" -> registry.counter("social.events", dimensions).increment(record.value)
            "bytes" -> DistributionSummary.builder("social.transfer").baseUnit("bytes").tags(dimensions)
                .serviceLevelObjectives(1024.0, 16384.0, 262144.0, 1048576.0, 16777216.0).register(registry).record(record.value)
            "queue_depth", "queue_age" -> DistributionSummary.builder(if (record.kind == "queue_depth") "social.queue.depth" else "social.queue.age")
                .baseUnit(if (record.kind == "queue_depth") "items" else "seconds").tags(common)
                .serviceLevelObjectives(1.0, 5.0, 15.0, 60.0, 300.0, 1800.0).register(registry).record(record.value)
        }
    }
    fun exporter(result: String, count: Double = 1.0) = registry.counter("social.telemetry.exports", tags("server", "jvm") + Tag.of("result", result)).increment(count)
    fun dropped(platform: String, count: Long) = registry.counter("social.telemetry.dropped", tags("client", platform)).increment(count.toDouble())
    fun diagnostic(record: SocialObservation, side: String, platform: String): JsonObject = buildJsonObject {
        put("event", "social_operation_completed"); put("application", application); put("environment", environment)
        put("feature", record.feature); put("operation", record.operation); put("provider", record.provider)
        put("result", record.result); put("side", side); put("platform", platform)
        record.trace?.let { put("trace_id", it.traceId); put("span_id", it.spanId) }
    }
    companion object {
        private val LATENCY_BUCKETS = doubleArrayOf(.01, .05, .1, .25, .5, 1.0, 2.5, 5.0, 10.0, 30.0)
            .map { Duration.ofNanos((it * 1e9).toLong()) }.toTypedArray()
    }
}
