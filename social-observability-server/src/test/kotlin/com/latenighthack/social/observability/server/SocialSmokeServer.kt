package com.latenighthack.social.observability.server

import com.latenighthack.social.observability.*
import com.latenighthack.social.observability.client.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import io.micrometer.prometheus.*
import kotlinx.coroutines.runBlocking

/** Local fixture exercises the real client exporter and parent relay against the LGTM collector. */
fun main() = runBlocking {
    val port = System.getenv("SOCIAL_SMOKE_PORT")?.toInt() ?: 9149
    val collector = System.getenv("SOCIAL_SMOKE_COLLECTOR") ?: "http://localhost:4318"
    val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    val telemetry = SocialServerTelemetry(registry, "social-smoke", "test")
    val relay = SocialReportRelay(telemetry, { it.request.headers["Authorization"] == "Bearer local-smoke" },
        "$collector/v1/traces", "$collector/v1/logs")
    val server = embeddedServer(CIO, port = port) {
        routing {
            relay.install(this)
            get("/metrics") { call.respondText(registry.scrape()) }
        }
    }.start(wait = false)
    val client = SocialClientTelemetry("http://127.0.0.1:$port", "jvm",
        { mapOf("Authorization" to "Bearer local-smoke") }, HttpClient(ClientCIO))
    listOf("account", "profiles", "login", "rooms", "messages", "remote_content", "contacts", "typing", "read_receipts", "avatars", "debug")
        .forEach(client::feature)
    client.measure("login", "authenticateSocial", "email") { result("unauthorized") }
    client.measure("account", "createAccount") { }
    client.measure("profiles", "createProfile") { }
    try { client.measure("messages", "send") { error("fixture failure; excluded from telemetry") } } catch (_: IllegalStateException) {}
    client.event("messages", "queue", kind = "queue_depth", value = 2.0)
    client.event("messages", "queue", kind = "queue_age", value = 15.0)
    client.close(); client.awaitClosed()
    println("Social observability smoke server ready on $port")
    Runtime.getRuntime().addShutdownHook(Thread { server.stop(500, 1000); relay.close(); registry.close() })
    java.util.concurrent.CountDownLatch(1).await()
}
