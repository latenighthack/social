package com.latenighthack.social.remotecontent.service

import com.latenighthack.social.observability.*

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.contentLength
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.ByteArrayOutputStream
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
fun Routing.remoteContent(store: ContentStore, maxUploadBytes: Int = 16 * 1024 * 1024) = remoteContent(store, NoopSocialTelemetry, maxUploadBytes)

fun Routing.remoteContent(store: ContentStore, telemetry: SocialTelemetry, maxUploadBytes: Int = 16 * 1024 * 1024) {
    require(maxUploadBytes > 0)
    val slots = Semaphore(4)
    route(ContentUrls.CONTENT_PATH) {
        put("/{id}") {
            telemetry.measure("remote_content", "upload") {
            val id = call.parameters["id"]?.let(ContentUrls::decodeId)
            if (id == null) {
                result(socialHttpResult(HttpStatusCode.BadRequest.value)); call.respond(HttpStatusCode.BadRequest)
                return@measure
            }
            val token = call.request.queryParameters["upload_token"]?.let(ContentUrls::decodeId)
            if (token == null || token.size != 32) {
                result(socialHttpResult(HttpStatusCode.Forbidden.value)); call.respond(HttpStatusCode.Forbidden)
                return@measure
            }
            if ((call.request.contentLength() ?: 0) > maxUploadBytes) {
                result(socialHttpResult(HttpStatusCode.PayloadTooLarge.value)); call.respond(HttpStatusCode.PayloadTooLarge); return@measure
            }
            try {
                val bytes = slots.withPermit {
                    val channel = call.receiveChannel()
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = channel.readAvailable(buffer)
                        if (count < 0) break
                        if (output.size().toLong() + count > maxUploadBytes) throw ContentTooLarge()
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                telemetry.measure("remote_content", "storage") { store.put(id, bytes, token) }
            } catch (_: ContentTooLarge) {
                result(socialHttpResult(HttpStatusCode.PayloadTooLarge.value)); call.respond(HttpStatusCode.PayloadTooLarge); return@measure
            } catch (error: UploadRejected) {
                call.respond(if (error.conflict) HttpStatusCode.Conflict else HttpStatusCode.Forbidden)
                return@measure
            }
            call.respond(HttpStatusCode.OK)
                    }
        }
        get("/{id}") {
            telemetry.measure("remote_content", "download") {
            val id = call.parameters["id"]?.let(ContentUrls::decodeId)
            if (id == null) {
                result(socialHttpResult(HttpStatusCode.BadRequest.value)); call.respond(HttpStatusCode.BadRequest)
                return@measure
            }
            val content = telemetry.measure("remote_content", "storage") { store.get(id) }
            if (content == null) {
                result(socialHttpResult(HttpStatusCode.NotFound.value)); call.respond(HttpStatusCode.NotFound)
                return@measure
            }
            val declaredType = content.mimeType?.let { runCatching { ContentType.parse(it) }.getOrNull() }
            val safeInline = declaredType?.withoutParameters()?.toString() in SAFE_INLINE_TYPES
            val contentType = if (safeInline) declaredType!! else ContentType.Application.OctetStream
            call.response.headers.append("X-Content-Type-Options", "nosniff")
            call.response.headers.append("Content-Security-Policy", "default-src 'none'; sandbox")
            if (!safeInline) call.response.headers.append("Content-Disposition", "attachment; filename=content")
            call.respondBytes(content.bytes, contentType)
                    }
        }
    }
}

private class ContentTooLarge : Exception()

private val SAFE_INLINE_TYPES = setOf("image/png", "image/jpeg", "image/gif", "image/webp", "image/avif",
    "video/mp4", "video/webm", "audio/mpeg", "audio/ogg", "audio/wav", "audio/mp4")
