package com.latenighthack.social.observability.server

import java.net.URLDecoder

/** Server-only collector configuration. No collector credentials enter client reports. */
data class SocialCollectorConfig(val tracesEndpoint: String?, val logsEndpoint: String?, val headers: Map<String, String>) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): SocialCollectorConfig {
            val disabled = env["OTEL_SDK_DISABLED"] == "true"
            fun endpoint(signal: String): String? {
                if (disabled || env["OTEL_${signal}_EXPORTER"] == "none") return null
                env["SOCIAL_${signal}_OTLP_HTTP_ENDPOINT"]?.takeIf(String::isNotBlank)?.let { return it }
                if ((env["OTEL_EXPORTER_OTLP_${signal}_PROTOCOL"] ?: env["OTEL_EXPORTER_OTLP_PROTOCOL"]) == "grpc") return null
                return env["OTEL_EXPORTER_OTLP_${signal}_ENDPOINT"]?.takeIf(String::isNotBlank)
                    ?: env["OTEL_EXPORTER_OTLP_ENDPOINT"]?.takeIf(String::isNotBlank)?.trimEnd('/')?.plus("/v1/${signal.lowercase()}")
            }
            val headers = env["OTEL_EXPORTER_OTLP_HEADERS"].orEmpty().split(',').filter { '=' in it }.associate {
                val (key, value) = it.split('=', limit = 2)
                key.trim() to URLDecoder.decode(value.trim(), Charsets.UTF_8)
            }
            return SocialCollectorConfig(endpoint("TRACES"), endpoint("LOGS"), headers)
        }
    }
}
