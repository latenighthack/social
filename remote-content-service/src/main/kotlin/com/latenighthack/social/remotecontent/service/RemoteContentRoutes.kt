package com.latenighthack.social.remotecontent.service

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/**
 * Mounts the raw HTTP upload/download endpoints for remote content: a PUT stores
 * the raw request body under the content id, and a GET streams the bytes back with
 * the MIME type recorded at create time (falling back to application/octet-stream).
 */
fun Routing.remoteContent(store: ContentStore) {
    route(ContentUrls.CONTENT_PATH) {
        put("/{id}") {
            val id = call.parameters["id"]?.let(ContentUrls::decodeId)
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest)
                return@put
            }
            store.put(id, call.receive<ByteArray>())
            call.respond(HttpStatusCode.OK)
        }
        get("/{id}") {
            val id = call.parameters["id"]?.let(ContentUrls::decodeId)
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest)
                return@get
            }
            val content = store.get(id)
            if (content == null) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            val contentType = content.mimeType
                ?.let { runCatching { ContentType.parse(it) }.getOrNull() }
                ?: ContentType.Application.OctetStream
            call.respondBytes(content.bytes, contentType)
        }
    }
}
