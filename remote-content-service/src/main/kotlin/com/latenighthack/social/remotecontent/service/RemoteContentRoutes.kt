package com.latenighthack.social.remotecontent.service

import com.latenighthack.social.observability.*
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.*

/** Raw transfer routes; the original overload remains available to standalone hosts. */
fun Routing.remoteContent(store: ContentStore) = remoteContent(store, NoopSocialTelemetry)

fun Routing.remoteContent(store: ContentStore, telemetry: SocialTelemetry) {
    route(ContentUrls.CONTENT_PATH) {
        put("/{id}") {
            telemetry.measure("remote_content", "upload") {
                val id = call.parameters["id"]?.let(ContentUrls::decodeId)
                if (id == null) { result("invalid"); call.respond(HttpStatusCode.BadRequest); return@measure }
                val bytes = call.receive<ByteArray>()
                telemetry.measure("remote_content", "storage") { store.put(id, bytes) }
                telemetry.event("remote_content", "upload", kind = "bytes", value = bytes.size.toDouble())
                call.respond(HttpStatusCode.OK)
            }
        }
        get("/{id}") {
            telemetry.measure("remote_content", "download") {
                val id = call.parameters["id"]?.let(ContentUrls::decodeId)
                if (id == null) { result("invalid"); call.respond(HttpStatusCode.BadRequest); return@measure }
                val content = telemetry.measure("remote_content", "storage") { store.get(id) }
                if (content == null) { result("not_found"); call.respond(HttpStatusCode.NotFound); return@measure }
                val contentType = content.mimeType?.let { runCatching { ContentType.parse(it) }.getOrNull() }
                    ?: ContentType.Application.OctetStream
                telemetry.event("remote_content", "download", kind = "bytes", value = content.bytes.size.toDouble())
                call.respondBytes(content.bytes, contentType)
            }
        }
    }
}
