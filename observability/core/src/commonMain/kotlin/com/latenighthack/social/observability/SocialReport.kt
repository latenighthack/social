package com.latenighthack.social.observability

import kotlinx.serialization.json.*

const val SOCIAL_REPORT_PATH = "/api/telemetry/social/v1/reports"
const val SOCIAL_REPORT_MAX_BYTES = 65_536
const val SOCIAL_REPORT_MAX_RECORDS = 32

data class SocialObservation(
    val feature: String,
    val operation: String,
    val provider: String = "none",
    val result: String = "ok",
    val durationNanos: Long = 0,
    val kind: String = "operation",
    val value: Double = 1.0,
    val startTimeUnixNano: Long = 0,
    val trace: SocialTraceIds? = null,
) {
    fun validate() {
        require(operation in SocialCatalogue.operations[feature].orEmpty())
        require(provider in SocialCatalogue.providers && result in SocialCatalogue.results && kind in SocialCatalogue.kinds)
        require(value.isFinite() && value >= 0 && value <= 1e12)
        require(durationNanos in 0..604_800_000_000_000L && startTimeUnixNano >= 0)
        require(startTimeUnixNano <= Long.MAX_VALUE - durationNanos)
        trace?.let {
            require(kind == "operation" && startTimeUnixNano > 0)
            fun valid(id: String, size: Int) = id.matches(Regex("[0-9a-f]{$size}")) && id.any { c -> c != '0' }
            require(valid(it.traceId, 32) && valid(it.spanId, 16))
            require(it.parentSpanId == null || valid(it.parentSpanId, 16))
        }
    }
    fun toJson(): JsonObject = buildJsonObject {
        put("feature", feature); put("operation", operation); put("provider", provider); put("result", result)
        put("durationNanos", durationNanos.toString()); put("kind", kind); put("value", value)
        put("startTimeUnixNano", startTimeUnixNano.toString())
        trace?.let { ids -> putJsonObject("trace") {
            put("traceId", ids.traceId); put("spanId", ids.spanId)
            ids.parentSpanId?.let { put("parentSpanId", it) }
        } }
    }
    companion object {
        fun fromJson(input: JsonObject): SocialObservation {
            require(input.keys.all { it in setOf("feature", "operation", "provider", "result", "durationNanos", "kind", "value", "startTimeUnixNano", "trace") })
            fun field(key: String) = input.getValue(key).jsonPrimitive.content
            val trace = input["trace"]?.jsonObject?.let {
                require(it.keys.all { key -> key in setOf("traceId", "spanId", "parentSpanId") })
                SocialTraceIds(it.getValue("traceId").jsonPrimitive.content, it.getValue("spanId").jsonPrimitive.content,
                    it["parentSpanId"]?.jsonPrimitive?.content)
            }
            return SocialObservation(field("feature"), field("operation"), field("provider"), field("result"),
                field("durationNanos").toLong(), field("kind"), field("value").toDouble(), field("startTimeUnixNano").toLong(), trace).also { it.validate() }
        }
    }
}

data class SocialReport(val platform: String, val records: List<SocialObservation>, val dropped: Long = 0) {
    fun toJson(): JsonObject = buildJsonObject {
        put("version", 1); put("platform", platform); put("dropped", dropped.toString())
        put("records", JsonArray(records.map { it.toJson() }))
    }
    companion object {
        fun decode(text: String): SocialReport {
            val input = Json.parseToJsonElement(text).jsonObject
            require(input.keys == setOf("version", "platform", "dropped", "records"))
            require(input.getValue("version").jsonPrimitive.int == 1)
            val platform = input.getValue("platform").jsonPrimitive.content
            require(platform in SocialCatalogue.platforms)
            val dropped = input.getValue("dropped").jsonPrimitive.long
            require(dropped in 0..1_000_000)
            val records = input.getValue("records").jsonArray
            require(records.size in 1..SOCIAL_REPORT_MAX_RECORDS)
            return SocialReport(platform, records.map { SocialObservation.fromJson(it.jsonObject) }, dropped)
        }
    }
}
