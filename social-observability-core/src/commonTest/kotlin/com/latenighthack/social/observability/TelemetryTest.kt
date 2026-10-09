package com.latenighthack.social.observability

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class TelemetryTest {
    private class Sink : SocialTelemetry {
        val records = mutableListOf<SocialObservation>()
        override fun record(observation: SocialObservation) { observation.validate(); records += observation }
    }
    @Test fun failuresAndCancellationPreserveIdentityAndDoNotLeakMessages() = runTest {
        val sink = Sink()
        val error = IllegalStateException("private-token")
        val caught = assertFailsWith<IllegalStateException> { sink.measure("account", "createAccount") { throw error } }
        assertSame(error, caught)
        val cancelled = CancellationException("private-cancel")
        assertSame(cancelled, assertFailsWith<CancellationException> { sink.measure("messages", "send") { throw cancelled } })
        assertEquals(listOf("error", "cancelled"), sink.records.map { it.result })
        assertFalse(sink.records.toString().contains("private"))
        val broken = object : SocialTelemetry { override fun record(observation: SocialObservation) { error("sink broken") } }
        assertEquals(42, broken.measure("account", "createAccount") { 42 })
    }
    @Test fun disabledTelemetryAndBrokenSpanCreationPreserveResults() = runTest {
        var calls = 0
        assertEquals(7, NoopSocialTelemetry.measure("account", "createAccount") { calls++; 7 })
        val broken = object : SocialTelemetry {
            override suspend fun begin(feature: String, operation: String, provider: String): SocialSpanScope = throw CancellationException("exporter failure")
            override fun record(observation: SocialObservation) {}
        }
        assertEquals(8, broken.measure("account", "createAccount") { calls++; 8 })
        assertEquals(2, calls)
    }
    @Test fun rejectedOperationsKeepTheirClassificationWhenTheLibraryThrows() = runTest {
        val sink = Sink()
        val denial = IllegalStateException("private denial")
        assertSame(denial, assertFailsWith<IllegalStateException> {
            sink.measure("rooms", "joinByCode") { result("expired"); throw denial }
        })
        assertEquals("rejected", SocialCatalogue.outcome(sink.records.single().result))
    }
    @Test fun collectionRemainsLazyAndHasOneCollector() = runTest {
        val sink = Sink(); var collectors = 0
        val observed = flow { collectors++; emit(1); emit(2) }.socialObserved(sink, "profiles")
        assertEquals(0, collectors); assertTrue(sink.records.isEmpty())
        assertEquals(listOf(1,2), observed.toList())
        assertEquals(1, collectors); assertEquals(1, sink.records.size)
        assertEquals(listOf(1,2), observed.toList()); assertEquals(2, collectors)
    }
    @Test fun httpDenialsAreNotInfrastructureFailures() {
        assertEquals("rejected", SocialCatalogue.outcome(socialHttpResult(401)))
        assertEquals("rejected", SocialCatalogue.outcome(socialHttpResult(404)))
        assertEquals("error", SocialCatalogue.outcome(socialHttpResult(503)))
    }
    @Test fun reportRoundTripsAndRejectsUnboundedOrPrivateFields() {
        val record = SocialObservation("login", "authenticateSocial", "apple", "unauthorized")
        val report = SocialReport("ios", listOf(record), 2)
        assertEquals(report, SocialReport.decode(report.toJson().toString()))
        assertEquals("rejected", SocialCatalogue.outcome(record.result))
        assertEquals("needs_binding", socialResult("LOGIN_RESULT_NEEDS_BINDING"))
        assertFailsWith<IllegalArgumentException> { SocialReport.decode(report.toJson().toString().replace("authenticateSocial", "private-user-id")) }
        assertFailsWith<IllegalArgumentException> { SocialReport.decode(report.toJson().toString().replace("\"version\":1", "\"version\":1,\"token\":\"secret\"")) }
        assertFailsWith<IllegalArgumentException> { SocialObservation("messages", "queue", value = Double.NaN).validate() }
    }
}
