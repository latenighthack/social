package com.latenighthack.social.observability.server

import com.latenighthack.social.observability.*
import io.micrometer.prometheus.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class RelayTest {
    @Test fun scrapeHasSecondsAndClassifiesExpectedRejections() = runBlocking {
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        val telemetry = SocialServerTelemetry(registry, "test", "local")
        telemetry.feature("login"); telemetry.provider("email", true); telemetry.provider("apple", false)
        telemetry.measure("login", "authenticateSocial", "apple") { result("unauthorized") }
        val scrape = registry.scrape()
        assertTrue(scrape.contains("social_operations_total"))
        assertTrue(scrape.contains("social_operation_duration_seconds_bucket"))
        assertTrue(scrape.contains("outcome=\"rejected\"")); assertFalse(scrape.contains("outcome=\"error\""))
        assertTrue(scrape.contains("social_login_provider_enabled"))
        assertFalse(scrape.contains("trace_id=")); registry.close()
    }
    @Test fun reportsAreAcceptedDuringCollectorOutageAndRejectUnknownFields() = testApplication {
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        val telemetry = SocialServerTelemetry(registry, "test", "local")
        val forwarded = mutableListOf<String>()
        val relay = SocialReportRelay(telemetry, { true }, "http://collector/v1/traces", "http://collector/v1/logs",
            client = HttpClient(MockEngine { request -> forwarded += (request.body as TextContent).text; respond("", HttpStatusCode.ServiceUnavailable) }))
        application { routing { relay.install(this) } }
        val record = SocialObservation("messages", "send", result = "error", durationNanos = 1_000,
            startTimeUnixNano = 100_000, trace = SocialTraceIds("1".repeat(32), "2".repeat(16)))
        val body = SocialReport("ios", listOf(record,
            SocialObservation("messages", "queue", kind = "queue_depth", value = 0.0),
            SocialObservation("messages", "queue", kind = "queue_age", value = 15.0),
            SocialObservation("runtime", "export", kind = "event", result = "error", value = 2.0))).toJson().toString()
        assertEquals(HttpStatusCode.Accepted, client.post(SOCIAL_REPORT_PATH) { contentType(ContentType.Application.Json); setBody(body) }.status)
        assertEquals(HttpStatusCode.BadRequest, client.post(SOCIAL_REPORT_PATH) { contentType(ContentType.Application.Json); setBody(body.replace("\"version\":1", "\"version\":1,\"credential\":\"private\"")) }.status)
        assertEquals(HttpStatusCode.PayloadTooLarge, client.post(SOCIAL_REPORT_PATH) { contentType(ContentType.Application.Json); setBody("x".repeat(65_537)) }.status)
        relay.close(); relay.awaitClosed()
        assertEquals(1.0, registry.find("social.operations").counter()!!.count())
        assertEquals(2.0, registry.find("social.telemetry.exports").tags("side", "client", "result", "error").counter()!!.count())
        assertEquals(2, forwarded.size)
        assertTrue(forwarded.any { it.contains("trace_id") }); assertTrue(forwarded.any { it.contains("resourceSpans") })
        assertFalse(forwarded.joinToString().contains("private")); registry.close()
    }
    @Test fun hostAuthorizationRejectsReportsBeforeRecording() = testApplication {
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        val relay = SocialReportRelay(SocialServerTelemetry(registry), { false }, null, null)
        application { routing { relay.install(this) } }
        assertEquals(HttpStatusCode.Unauthorized, client.post(SOCIAL_REPORT_PATH) { contentType(ContentType.Application.Json); setBody("{}") }.status)
        assertNull(registry.find("social.operations").counter())
        relay.close(); relay.awaitClosed(); registry.close()
    }
}
