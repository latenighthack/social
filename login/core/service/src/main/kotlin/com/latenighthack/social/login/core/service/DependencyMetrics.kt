@file:Suppress("TooGenericExceptionCaught")

package com.latenighthack.social.login.core.service

import com.latenighthack.social.observability.*
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Gauge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import java.util.concurrent.TimeUnit

/** Provider/operation are assigned by the host, never taken from credentials or URLs. */
class DependencyMetrics(private val meters: MeterRegistry) {
    fun configured(provider: String, value: Boolean) {
        Gauge.builder("fullhouse.dependency.configured") { if (value) 1.0 else 0.0 }
            .tag("provider", provider).register(meters)
    }
    fun record(provider: String, operation: String, outcome: String, nanos: Long) {
        meters.timer("fullhouse.dependency.duration", "provider", provider, "operation", operation, "outcome", outcome)
            .record(nanos, TimeUnit.NANOSECONDS)
    }
    suspend fun <T> measure(provider: String, operation: String, accepted: (T) -> Boolean = { true }, block: suspend () -> T): T {
        val start = System.nanoTime()
        var outcome = "success"
        try { return block().also { if (!accepted(it)) outcome = "rejected" } }
        catch (error: TimeoutCancellationException) { outcome = "timeout"; throw error }
        catch (error: CancellationException) { outcome = "cancelled"; throw error }
        catch (error: Exception) { outcome = "error"; throw error }
        finally { record(provider, operation, outcome, System.nanoTime() - start) }
    }
    fun verifier(provider: String, delegate: SocialTokenVerifier?): SocialTokenVerifier? {
        configured(provider, delegate != null)
        return delegate?.let { object : SocialTokenVerifier, SocialTelemetryOwner {
            override var socialTelemetry: SocialTelemetry
                get() = (it as? SocialTelemetryOwner)?.socialTelemetry ?: NoopSocialTelemetry
                set(value) { (it as? SocialTelemetryOwner)?.socialTelemetry = value }
            override suspend fun verify(idToken: String): VerifiedClaims? =
                measure(provider, "verify", { it != null }) { it.verify(idToken) }
        } }
    }
    fun email(delegate: EmailSender?): EmailSender? {
        configured("email", delegate != null)
        return delegate?.let { object : EmailSender {
            override suspend fun sendMagicLink(email: String, link: String) = measure("email", "send") { it.sendMagicLink(email, link) }
        } }
    }
    fun sms(delegate: SmsSender?): SmsSender? {
        configured("sms", delegate != null)
        return delegate?.let { object : SmsSender {
            override suspend fun sendCode(phoneNumber: String, code: String) = measure("sms", "send") { it.sendCode(phoneNumber, code) }
        } }
    }
}
