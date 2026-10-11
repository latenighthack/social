package com.latenighthack.social.observability.client

import com.latenighthack.social.observability.*
import io.ktor.client.request.get
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ClientTelemetryTest {
    @Test fun ambiguousUploadIsNotRetriedAndNeverChangesOperationResult() = runTest { withContext(Dispatchers.Default) {
        var attempts = 0
        val telemetry = SocialClientTelemetry("http://server", "jvm", { mapOf("Authorization" to "private") },
            HttpClient(MockEngine { attempts++; throw IllegalStateException("lost acknowledgement") }), flushMillis = 1)
        assertEquals(42, telemetry.measure("account", "createAccount") { 42 })
        telemetry.close(); telemetry.awaitClosed()
        assertEquals(1, attempts)
    } }
    @Test fun explicitRejectionRetriesOnceWithoutInstrumentingExport() = runTest { withContext(Dispatchers.Default) {
        var attempts = 0
        val bodies = mutableListOf<String>()
        val telemetry = SocialClientTelemetry("http://server", "ios", { mapOf("Attestation" to "private") },
            HttpClient(MockEngine { request ->
                attempts++; assertNull(request.headers["traceparent"])
                assertEquals("private", request.headers["Attestation"])
                bodies += (request.body as TextContent).text
                respond("{}", if (attempts == 1) HttpStatusCode.TooManyRequests else HttpStatusCode.Accepted)
            }), flushMillis = 1)
        telemetry.measure("messages", "send") { }
        telemetry.close(); telemetry.awaitClosed()
        assertEquals(2, attempts); assertEquals(bodies[0], bodies[1])
        val report = SocialReport.decode(bodies.last())
        assertEquals(1, report.records.size); assertNotNull(report.records.single().trace)
        assertFalse(bodies.joinToString().contains("private"))
    } }
    @Test fun sharedHttpTransportPreservesIdentityDenialsAndExportIsolation() = runTest { withContext(Dispatchers.Default) {
        val reports = mutableListOf<SocialReport>()
        val uploads = HttpClient(MockEngine { request ->
            assertNull(request.headers["traceparent"])
            reports += SocialReport.decode((request.body as TextContent).text)
            respond("{}", HttpStatusCode.Accepted)
        })
        val telemetry = SocialClientTelemetry("http://server", "jvm", { emptyMap() }, uploads, flushMillis = 1)
        val application = HttpClient(MockEngine { request ->
            assertNotNull(request.headers["traceparent"])
            respond("denied", HttpStatusCode.Unauthorized)
        })
        try {
            assertSame(application, telemetry.instrumentHttpClient(application))
            assertSame(application, telemetry.instrumentHttpClient(application))
            assertFailsWith<IllegalArgumentException> { telemetry.instrumentHttpClient(uploads) }
            assertEquals(HttpStatusCode.Unauthorized, application.get("http://content/private").status)
            telemetry.close(); telemetry.awaitClosed()
            assertEquals("unauthorized", reports.flatMap { it.records }.single().result)
            assertFalse(reports.toString().contains("private"))
        } finally { application.close(); telemetry.close() }
    } }
    @Test fun httpSpansDistinguishRejectionsAndFailures() = runTest {
        val spans = mutableListOf<ClientTimingSpan>()
        for (status in listOf(200, 401, 404, 503)) {
            assertEquals(status, withClientHttpSpan("HTTP GET", spans::add, { it }) { status })
        }
        assertEquals(listOf("ok", "unauthorized", "not_found", "error"), spans.map { it.outcome })
    }
    @Test fun failedDeliveryIsReportedOnRecoveryWithoutReplayingOperations() = runTest { withContext(Dispatchers.Default) {
        val bodies = mutableListOf<SocialReport>()
        val firstAttempt = CompletableDeferred<Unit>()
        val accepted = CompletableDeferred<Unit>()
        val telemetry = SocialClientTelemetry("http://server", "jvm", { emptyMap() }, HttpClient(MockEngine { request ->
            bodies += SocialReport.decode((request.body as TextContent).text)
            if (bodies.size == 1) { firstAttempt.complete(Unit); throw IllegalStateException("private offline detail") }
            accepted.complete(Unit); respond("{}", HttpStatusCode.Accepted)
        }), flushMillis = 1)
        try {
            telemetry.measure("account", "createAccount") { }
            withTimeout(5_000) { firstAttempt.await() }
            telemetry.measure("profiles", "createProfile") { }
            withTimeout(5_000) { accepted.await() }
            telemetry.close(); telemetry.awaitClosed()
            val recovered = bodies.last()
            assertTrue(recovered.records.any { it.feature == "runtime" && it.operation == "export" && it.result == "error" && it.value == 1.0 })
            assertTrue(recovered.records.none { it.operation == "createAccount" })
            assertTrue(recovered.dropped > 0)
            assertFalse(bodies.toString().contains("private"))
        } finally { telemetry.close() }
    } }
    @Test fun fullQueueReportsDroppedDataInNextAcceptedBatch() = runTest { withContext(Dispatchers.Default) {
        val reports = mutableListOf<SocialReport>()
        val telemetry = SocialClientTelemetry("http://server", "android", { emptyMap() },
            HttpClient(MockEngine { request ->
                reports += SocialReport.decode((request.body as TextContent).text); respond("{}", HttpStatusCode.Accepted)
            }), flushMillis = 1, capacity = 1)
        repeat(100) { telemetry.event("runtime", "dropped") }
        telemetry.close(); telemetry.awaitClosed()
        assertTrue(reports.any { it.dropped > 0 })
        assertTrue(reports.flatMap { it.records }.size < 100)
    } }
}
