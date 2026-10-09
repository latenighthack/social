package com.latenighthack.social.login.core.service

import com.latenighthack.social.observability.*
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class DependencyMetricsTest {
    @Test fun verifierWrapperPreservesTelemetryOwnershipAndResults() = runBlocking {
        val delegate = object : SocialTokenVerifier, SocialTelemetryOwner {
            override var socialTelemetry: SocialTelemetry = NoopSocialTelemetry
            override suspend fun verify(idToken: String): VerifiedClaims? = null
        }
        val records = mutableListOf<SocialObservation>()
        val telemetry = object : SocialTelemetry {
            override fun record(observation: SocialObservation) { records += observation }
        }
        val meters = SimpleMeterRegistry()
        try {
            val wrapped = DependencyMetrics(meters).verifier("apple", delegate)!!
            (wrapped as SocialTelemetryOwner).socialTelemetry = telemetry
            assertSame(telemetry, delegate.socialTelemetry)
            assertNull(wrapped.verify("private-token"))
            assertEquals(1, meters.find("fullhouse.dependency.duration").tag("outcome", "rejected").timer()!!.count())
        } finally { meters.close() }
    }
}
